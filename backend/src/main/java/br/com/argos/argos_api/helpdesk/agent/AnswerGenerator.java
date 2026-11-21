package br.com.argos.argos_api.helpdesk.agent;

import br.com.argos.argos_api.ai.llm.LlmRouter;
import br.com.argos.argos_api.helpdesk.model.RetrievedPassage;
import br.com.argos.argos_api.helpdesk.observability.ExecutionTrace;
import br.com.argos.argos_api.helpdesk.prompt.PromptLibrary;
import br.com.argos.argos_api.helpdesk.retrieval.HybridRetriever;
import br.com.argos.argos_api.helpdesk.tool.DocumentationSearchTool;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Gerar resposta": builds the prompt (system rules, numbered passages, question), streams the answer through the
 * LLM router (primary model, fallbacks on failure) and runs the tool loop: when the model asks for more
 * documentation, the search runs and the model is called again with the results, at most {@code maxToolCalls} times.
 */
public class AnswerGenerator {

    public record Generation(String text, List<RetrievedPassage> passages, int toolCalls) {
    }

    private final LlmRouter router;
    private final PromptLibrary prompts;
    private final HybridRetriever retriever;
    private final int maxToolCalls;
    private final int maxPassages;

    public AnswerGenerator(LlmRouter router, PromptLibrary prompts, HybridRetriever retriever, int maxToolCalls,
                           int maxPassages) {
        this.router = router;
        this.prompts = prompts;
        this.retriever = retriever;
        this.maxToolCalls = maxToolCalls;
        this.maxPassages = maxPassages;
    }

    public boolean available() {
        return router.available();
    }

    /**
     * @throws br.com.argos.argos_api.ai.llm.AllRoutesFailedException when no model could answer
     * @throws LlmRouter.PartialResponseException when the model failed mid-answer
     */
    public Generation generate(String question, List<RetrievedPassage> passages, AgentListener listener,
                               ExecutionTrace trace) {
        // Tool searches may add up to two more sections beyond the initial context.
        PassageRegistry registry = new PassageRegistry(passages, maxPassages + 2 * maxToolCalls);
        DocumentationSearchTool tool = new DocumentationSearchTool(retriever, registry, trace, listener);
        List<ToolSpecification> specifications = ToolSpecifications.toolSpecificationsFrom(tool);
        Map<String, ToolExecutor> executors = executors(tool);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(prompts.system()));
        messages.add(UserMessage.from(prompts.user(registry.passages(), question)));

        int round = 0;
        while (true) {
            boolean toolsAllowed = round < maxToolCalls;
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(toolsAllowed ? specifications : List.of())
                    .build();
            ChatResponse response;
            ExecutionTrace.Span span = trace.start("generation.llm");
            try {
                response = router.stream(request, listener::onToken);
                AiMessage generated = response.aiMessage();
                span.attr("round", round + 1)
                        .attr("toolCalls", generated.hasToolExecutionRequests() ? generated.toolExecutionRequests().size() : 0)
                        .attr("finishReason", response.finishReason() == null ? null : response.finishReason().name());
            } catch (RuntimeException e) {
                span.fail(e);
                throw e;
            } finally {
                span.close();
            }
            AiMessage message = response.aiMessage();
            if (!toolsAllowed || !message.hasToolExecutionRequests()) {
                return new Generation(message.text() == null ? "" : message.text(), registry.passages(), tool.calls());
            }
            messages.add(message);
            for (ToolExecutionRequest toolRequest : message.toolExecutionRequests()) {
                ToolExecutor executor = executors.get(toolRequest.name());
                String result;
                ExecutionTrace.Span toolSpan = trace.start("tool." + toolRequest.name()).attr("arguments", toolRequest.arguments());
                try {
                    result = executor == null ? "Ferramenta desconhecida: " + toolRequest.name()
                            : executor.execute(toolRequest, null);
                } catch (RuntimeException e) {
                    toolSpan.fail(e);
                    result = "A busca falhou; responda com os trechos que já tem.";
                } finally {
                    toolSpan.close();
                }
                messages.add(ToolExecutionResultMessage.from(toolRequest, result));
            }
            round++;
        }
    }

    private static Map<String, ToolExecutor> executors(Object tool) {
        Map<String, ToolExecutor> executors = new HashMap<>();
        for (Method method : tool.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                executors.put(ToolSpecifications.toolSpecificationFrom(method).name(), new DefaultToolExecutor(tool, method));
            }
        }
        return executors;
    }
}
