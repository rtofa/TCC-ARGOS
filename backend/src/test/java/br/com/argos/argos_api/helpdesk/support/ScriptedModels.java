package br.com.argos.argos_api.helpdesk.support;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** Language models that follow a script, recording what they were asked. */
public final class ScriptedModels {

    private ScriptedModels() {
    }

    public static ChatResponse text(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).finishReason(FinishReason.STOP)
                .tokenUsage(new TokenUsage(500, 60)).build();
    }

    public static ChatResponse toolCall(String tool, String arguments) {
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("call-1").name(tool).arguments(arguments).build();
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(request))).finishReason(FinishReason.TOOL_EXECUTION)
                .tokenUsage(new TokenUsage(500, 20)).build();
    }

    /** Blocking model (used by the query-understanding step). */
    public static class Chat implements ChatModel {
        private final Supplier<ChatResponse> behaviour;
        public final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

        public Chat(Supplier<ChatResponse> behaviour) {
            this.behaviour = behaviour;
        }

        public static Chat answering(String json) {
            return new Chat(() -> text(json));
        }

        public static Chat failing(RuntimeException error) {
            return new Chat(() -> {
                throw error;
            });
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return behaviour.get();
        }
    }

    /** Streaming model that plays one scripted response per call (the last one repeats). */
    public static class Streaming implements StreamingChatModel {
        private final Deque<Supplier<ChatResponse>> script = new ArrayDeque<>();
        public final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

        @SafeVarargs
        public Streaming(Supplier<ChatResponse>... responses) {
            script.addAll(List.of(responses));
        }

        public static Streaming answering(String text) {
            return new Streaming(() -> text(text));
        }

        public static Streaming failing(RuntimeException error) {
            return new Streaming(() -> {
                throw error;
            });
        }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            requests.add(request);
            Supplier<ChatResponse> next = script.size() > 1 ? script.poll() : script.peek();
            ChatResponse response;
            try {
                response = next.get();
            } catch (RuntimeException e) {
                handler.onError(e);
                return;
            }
            String text = response.aiMessage().text();
            if (text != null && !text.isEmpty()) {
                int half = text.length() / 2;
                handler.onPartialResponse(text.substring(0, half));
                handler.onPartialResponse(text.substring(half));
            }
            handler.onCompleteResponse(response);
        }
    }
}
