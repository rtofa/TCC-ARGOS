package br.com.argos.argos_api.ai;

import br.com.argos.argos_api.ai.agent.AgentFactory;
import br.com.argos.argos_api.ai.agent.AssistantAgent;
import br.com.argos.argos_api.ai.agent.InvestigationTools;
import br.com.argos.argos_api.ai.agent.InvestigatorAgent;
import br.com.argos.argos_api.ai.agent.ReportValidator;
import br.com.argos.argos_api.ai.llm.CircuitBreakers;
import br.com.argos.argos_api.ai.llm.LlmRoute;
import br.com.argos.argos_api.ai.llm.LlmRouter;
import br.com.argos.argos_api.ai.memory.JdbcChatMemoryStore;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wiring of the AI module: one model per route (any provider), two routers (investigation and assistant, with
 * different reasoning effort) sharing the same circuit breakers, the two LangChain4j agents, and bounded worker pools.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    static final String SERVER_SIDE_FALLBACK_BETA = "server-side-fallback-2026-07-01";

    @Bean
    CircuitBreakers llmCircuitBreakers(AiProperties properties, Clock clock) {
        return new CircuitBreakers(properties.getCircuitFailureThreshold(), properties.getCircuitOpenDuration(), clock);
    }

    @Bean
    LlmRouter investigationRouter(AiProperties properties, CircuitBreakers llmCircuitBreakers) {
        return new LlmRouter("investigação", routes(properties, llmCircuitBreakers, properties.getInvestigationEffort()),
                properties.getInvestigationRateLimitWait());
    }

    @Bean
    LlmRouter assistantRouter(AiProperties properties, CircuitBreakers llmCircuitBreakers) {
        return new LlmRouter("assistente", routes(properties, llmCircuitBreakers, properties.getAssistantEffort()),
                properties.getAssistantRateLimitWait());
    }

    @Bean
    ReportValidator reportValidator(JsonMapper jsonMapper) {
        return new ReportValidator(jsonMapper);
    }

    @Bean
    InMemoryChatMemoryStore investigationMemoryStore() {
        return new InMemoryChatMemoryStore();
    }

    @Bean
    InvestigatorAgent investigatorAgent(@Qualifier("investigationRouter") LlmRouter router, InvestigationTools tools,
                                        ReportValidator validator, InMemoryChatMemoryStore investigationMemoryStore,
                                        AiProperties properties) {
        return AgentFactory.investigator(router, tools, validator, investigationMemoryStore,
                prompt("ai/prompts/investigator.md"), properties.getMaxToolCalls(), properties.getMaxOutputReprompts());
    }

    @Bean
    AssistantAgent assistantAgent(@Qualifier("assistantRouter") LlmRouter router, InvestigationTools tools,
                                  JdbcChatMemoryStore chatMemoryStore, AssistantContextProvider contextProvider,
                                  AiProperties properties) {
        String systemPrompt = prompt("ai/prompts/assistant.md");
        return AgentFactory.assistant(router, tools, chatMemoryStore,
                memoryId -> systemPrompt + "\n\n" + contextProvider.describe(String.valueOf(memoryId)),
                properties.getChatMemoryMaxMessages(), properties.getMaxToolCalls());
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor investigationExecutor(AiProperties properties) {
        return pool("argos-ai-investigation-", properties.getWorkerThreads(), properties.getWorkerThreads());
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor assistantExecutor(AiProperties properties) {
        return pool("argos-ai-assistant-", properties.getAssistantThreads(), properties.getAssistantThreads() * 4);
    }

    private static ThreadPoolTaskExecutor pool(String prefix, int threads, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(Math.max(1, threads));
        executor.setMaxPoolSize(Math.max(1, threads));
        executor.setQueueCapacity(Math.max(0, queue));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** Only routes with a key; each one keeps its own circuit breaker, keyed by model. */
    private static List<LlmRoute> routes(AiProperties properties, CircuitBreakers breakers, String effort) {
        return properties.activeRoutes().stream()
                .map(route -> new LlmRoute(route.getName(), route.getModel(), model(properties, route, effort),
                        breakers.forModel(route.getModel()), route.getInputPricePerMillion(), route.getOutputPricePerMillion(),
                        route.getCacheReadPricePerMillion()))
                .toList();
    }

    private static ChatModel model(AiProperties properties, AiProperties.Route route, String effort) {
        return AiProperties.ANTHROPIC.equalsIgnoreCase(route.getProvider())
                ? anthropic(properties, route, effort)
                : openAiCompatible(properties, route, effort);
    }

    /**
     * Any OpenAI-compatible API (Groq, Gemini, OpenRouter, a local Ollama…). These providers cache repeated prompt
     * prefixes automatically, which the stable system prompt + tools + briefing order is built for; on Groq cached
     * tokens also stop counting against the free tier's tokens-per-minute limit.
     */
    private static ChatModel openAiCompatible(AiProperties properties, AiProperties.Route route, String effort) {
        String reasoning = route.getReasoningEffort() == null ? effort : route.getReasoningEffort();
        return OpenAiChatModel.builder()
                .baseUrl(route.getBaseUrl())
                .apiKey(route.getApiKey())
                .modelName(route.getModel())
                .maxTokens(properties.getMaxTokens())
                .reasoningEffort(reasoning.isBlank() ? null : reasoning)
                .timeout(properties.getRequestTimeout())
                .maxRetries(properties.getMaxRetriesPerRoute())
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogRequests())
                .build();
    }

    /**
     * Claude through LangChain4j. Adaptive thinking is on (the current models always think) and thinking blocks are
     * returned and sent back so tool loops stay valid; one tool call per turn keeps a single thinking block per
     * response. Prompt caching covers the tool definitions, the system prompt and the growing conversation.
     */
    private static ChatModel anthropic(AiProperties properties, AiProperties.Route route, String effort) {
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("output_config", Map.of("effort", effort));
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .apiKey(route.getApiKey())
                .modelName(route.getModel())
                .maxTokens(properties.getMaxTokens())
                .timeout(properties.getRequestTimeout())
                .maxRetries(properties.getMaxRetriesPerRoute())
                .thinkingType("adaptive")
                .returnThinking(true)
                .sendThinking(true)
                .cacheTools(true)
                .cacheSystemMessages(true)
                .cacheAutomatically(true)
                .disableParallelToolUse(true)
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogRequests());
        if (route.isServerSideFallback()) {
            custom.put("fallbacks", "default");
            builder.beta(SERVER_SIDE_FALLBACK_BETA);
        }
        if (route.getBaseUrl() != null && !route.getBaseUrl().isBlank()) {
            builder.baseUrl(route.getBaseUrl());
        }
        return builder.customParameters(custom).build();
    }

    private static String prompt(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Prompt não encontrado: " + path, e);
        }
    }
}
