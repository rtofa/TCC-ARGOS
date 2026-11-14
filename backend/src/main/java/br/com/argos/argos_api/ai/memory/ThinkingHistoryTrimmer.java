package br.com.argos.argos_api.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Context engineering for multi-turn memory: keeps the model's thinking blocks only in the current turn.
 *
 * <p>Thinking blocks must go back unchanged while a tool loop is running, but in earlier turns they only cost tokens.
 * They are also tied to the exact conversation prefix, and the memory window drops old messages as the conversation
 * grows, which would invalidate them. Stripping them from completed turns keeps both the token bill and the
 * provider's history check happy.
 */
public final class ThinkingHistoryTrimmer implements UnaryOperator<ChatRequest> {

    @Override
    public ChatRequest apply(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                lastUser = i;
                break;
            }
        }
        if (lastUser <= 0) {
            return request;
        }
        List<ChatMessage> trimmed = new ArrayList<>(messages.size());
        boolean changed = false;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (i < lastUser && message instanceof AiMessage ai && (ai.thinking() != null || !ai.attributes().isEmpty())) {
                AiMessage.Builder plain = AiMessage.builder().text(ai.text());
                if (ai.hasToolExecutionRequests()) {
                    plain.toolExecutionRequests(ai.toolExecutionRequests());
                }
                trimmed.add(plain.build());
                changed = true;
            } else {
                trimmed.add(message);
            }
        }
        return changed ? request.toBuilder().messages(trimmed).build() : request;
    }
}
