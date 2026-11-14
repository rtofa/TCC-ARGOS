package br.com.argos.argos_api.ai.agent;

import br.com.argos.argos_api.ai.guardrail.AssistantGuardrails;
import br.com.argos.argos_api.ai.guardrail.InvestigationReportGuardrail;
import br.com.argos.argos_api.ai.memory.ThinkingHistoryTrimmer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.guardrail.config.OutputGuardrailsConfig;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.function.Function;

/** Builds the LangChain4j agents; shared by the Spring configuration and the tests so both run the same wiring. */
public final class AgentFactory {

    private AgentFactory() {
    }

    public static InvestigatorAgent investigator(ChatModel model, InvestigationTools tools, ReportValidator validator,
                                                 ChatMemoryStore memoryStore, String systemPrompt, int maxToolCalls,
                                                 int maxReprompts) {
        return AiServices.builder(InvestigatorAgent.class)
                .chatModel(model)
                .systemMessage(systemPrompt)
                .tools(tools)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder()
                        .id(id).maxMessages(200).chatMemoryStore(memoryStore).build())
                .chatRequestTransformer(new ThinkingHistoryTrimmer())
                .outputGuardrails(new InvestigationReportGuardrail(validator))
                .outputGuardrailsConfig(OutputGuardrailsConfig.builder().maxRetries(maxReprompts).build())
                // Hard stop above the soft budget enforced by the tools, which ask the agent to conclude first.
                .maxSequentialToolsInvocations(maxToolCalls + 4)
                .hallucinatedToolNameStrategy(request -> ToolExecutionResultMessage.from(request,
                        "A ferramenta '" + request.name() + "' não existe. Use apenas as ferramentas disponíveis."))
                // Smaller (free) models get arguments wrong more often: tell them what was wrong so they retry.
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
                // Tools already turn their own failures into text; anything else is a bug and must not leak.
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();
    }

    public static AssistantAgent assistant(ChatModel model, InvestigationTools tools, ChatMemoryStore memoryStore,
                                           Function<Object, String> systemPrompt, int memoryWindow, int maxToolCalls) {
        return AiServices.builder(AssistantAgent.class)
                .chatModel(model)
                .systemMessageProvider(systemPrompt)
                .tools(tools)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder()
                        .id(id).maxMessages(memoryWindow).chatMemoryStore(memoryStore).build())
                .chatRequestTransformer(new ThinkingHistoryTrimmer())
                .inputGuardrails(new AssistantGuardrails.Input())
                .outputGuardrails(new AssistantGuardrails.Output())
                .maxSequentialToolsInvocations(maxToolCalls)
                .hallucinatedToolNameStrategy(request -> ToolExecutionResultMessage.from(request,
                        "A ferramenta '" + request.name() + "' não existe. Use apenas as ferramentas disponíveis."))
                // Smaller (free) models get arguments wrong more often: tell them what was wrong so they retry.
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
                // Tools already turn their own failures into text; anything else is a bug and must not leak.
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();
    }
}
