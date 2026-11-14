package br.com.argos.argos_api.ai.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkingHistoryTrimmerTest {

    private static final ToolExecutionRequest TOOL_CALL =
            ToolExecutionRequest.builder().id("t1").name("saude_do_servico").arguments("{\"servico\":\"checkout\"}").build();

    private static AiMessage thinkingToolCall() {
        return AiMessage.builder().thinking("").toolExecutionRequests(List.of(TOOL_CALL))
                .attributes(Map.of("thinking_signature", "sig-123")).build();
    }

    @Test
    void stripsThinkingFromCompletedTurnsAndKeepsTheCurrentToolLoop() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("sistema"),
                UserMessage.from("primeira pergunta"),
                thinkingToolCall(),
                ToolExecutionResultMessage.from(TOOL_CALL, "dados"),
                AiMessage.builder().text("resposta 1").attributes(Map.of("thinking_signature", "sig-456")).build(),
                UserMessage.from("segunda pergunta"),
                thinkingToolCall(),
                ToolExecutionResultMessage.from(TOOL_CALL, "dados"));

        ChatRequest trimmed = new ThinkingHistoryTrimmer().apply(ChatRequest.builder().messages(messages).build());

        AiMessage oldToolCall = (AiMessage) trimmed.messages().get(2);
        AiMessage oldAnswer = (AiMessage) trimmed.messages().get(4);
        AiMessage currentToolCall = (AiMessage) trimmed.messages().get(6);
        assertThat(oldToolCall.attributes()).isEmpty();
        assertThat(oldToolCall.toolExecutionRequests()).containsExactly(TOOL_CALL);
        assertThat(oldAnswer.attributes()).isEmpty();
        assertThat(oldAnswer.text()).isEqualTo("resposta 1");
        assertThat(currentToolCall.attributes()).containsEntry("thinking_signature", "sig-123");
    }

    @Test
    void persistedMemoryKeepsTheThinkingSignatureNeededByAnOngoingToolLoop() {
        List<ChatMessage> roundTrip = ChatMessageDeserializer.messagesFromJson(
                ChatMessageSerializer.messagesToJson(List.of(UserMessage.from("pergunta"), thinkingToolCall())));

        AiMessage restored = (AiMessage) roundTrip.get(1);
        assertThat(restored.attributes()).containsEntry("thinking_signature", "sig-123");
        assertThat(restored.toolExecutionRequests()).containsExactly(TOOL_CALL);
    }
}
