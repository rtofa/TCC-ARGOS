package br.com.argos.argos_api.ai;

import br.com.argos.argos_api.ai.embedding.ResilientEmbeddingModel;
import br.com.argos.argos_api.ai.llm.LlmRoute;
import br.com.argos.argos_api.ai.llm.LlmRouter;
import br.com.argos.argos_api.ai.rerank.ResilientScoringModel;
import br.com.argos.argos_api.shared.resilience.CircuitBreakers;
import br.com.argos.argos_api.shared.resilience.ResilientCall;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.cohere.CohereScoringModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.scoring.ScoringModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The AI model layer, built with LangChain4j:
 *
 * <ul>
 *   <li>{@link LlmRouter}: one chat model per configured route (primary + fallbacks, any provider), blocking and
 *       streaming, with retries, backoff and a circuit breaker per model.</li>
 *   <li>{@link EmbeddingModel}: only when an embedding key is configured.</li>
 *   <li>{@link ScoringModel} (reranker): only when a rerank key is configured.</li>
 * </ul>
 *
 * Features consume the LangChain4j interfaces; a missing embedding or rerank bean means that capability is off.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiConfiguration.class);

    static final String SERVER_SIDE_FALLBACK_BETA = "server-side-fallback-2026-07-01";

    @Bean
    CircuitBreakers aiCircuitBreakers(AiProperties properties, Clock clock) {
        return new CircuitBreakers(properties.getCircuitFailureThreshold(), properties.getCircuitOpenDuration(), clock);
    }

    @Bean
    LlmRouter llmRouter(AiProperties properties, CircuitBreakers aiCircuitBreakers) {
        List<LlmRoute> routes = routes(properties, aiCircuitBreakers);
        log.info("Modelos de linguagem configurados: {}", routes.isEmpty() ? "nenhum"
                : routes.stream().map(route -> route.name() + "=" + route.model()).toList());
        return new LlmRouter("llm", routes, properties.getRateLimitWait(), properties.getRetry().toPolicy(),
                properties.getRequestTimeout().multipliedBy(2), d -> Thread.sleep(d.toMillis()));
    }

    @Bean
    @Conditional(EmbeddingConfigured.class)
    EmbeddingModel embeddingModel(AiProperties properties, CircuitBreakers aiCircuitBreakers) {
        AiProperties.Embedding config = properties.getEmbedding();
        EmbeddingModel openAi = OpenAiEmbeddingModel.builder()
                .baseUrl(blankToNull(config.getBaseUrl()))
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .dimensions(config.getDimensions())
                .timeout(config.getTimeout())
                .maxRetries(0)
                .maxSegmentsPerBatch(config.getBatchSize())
                .logRequests(properties.isLogRequests())
                .logResponses(false)
                .build();
        String dependency = "embedding:" + config.getModel();
        return new ResilientEmbeddingModel(openAi, resilientCall(dependency, properties, aiCircuitBreakers),
                config.getModel(), config.getDimensions());
    }

    @Bean
    @Conditional(RerankConfigured.class)
    ScoringModel rerankModel(AiProperties properties, CircuitBreakers aiCircuitBreakers) {
        AiProperties.Rerank config = properties.getRerank();
        CohereScoringModel.CohereScoringModelBuilder builder = CohereScoringModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .timeout(config.getTimeout())
                .maxRetries(0)
                .logRequests(properties.isLogRequests())
                .logResponses(false);
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            builder.baseUrl(config.getBaseUrl());
        }
        String dependency = "rerank:" + config.getModel();
        return new ResilientScoringModel(builder.build(), resilientCall(dependency, properties, aiCircuitBreakers),
                config.getModel());
    }

    private static ResilientCall resilientCall(String dependency, AiProperties properties, CircuitBreakers breakers) {
        Predicate<Throwable> transientError = LlmRouter::isTransient;
        return new ResilientCall(dependency, breakers.forDependency(dependency), properties.getRetry().toPolicy(),
                transientError);
    }

    /** Only usable routes (model + key); each one keeps its own circuit breaker, keyed by model. */
    private static List<LlmRoute> routes(AiProperties properties, CircuitBreakers breakers) {
        return properties.activeRoutes().stream()
                .map(route -> new LlmRoute(route.getName(), route.getModel(), model(properties, route),
                        streamingModel(properties, route), breakers.forModel(route.getModel()),
                        route.getInputPricePerMillion(), route.getOutputPricePerMillion(),
                        route.getCacheReadPricePerMillion()))
                .toList();
    }

    private static boolean isAnthropic(AiProperties.Route route) {
        return AiProperties.ANTHROPIC.equalsIgnoreCase(route.getProvider());
    }

    private static ChatModel model(AiProperties properties, AiProperties.Route route) {
        return isAnthropic(route) ? anthropic(properties, route) : openAiCompatible(properties, route);
    }

    private static StreamingChatModel streamingModel(AiProperties properties, AiProperties.Route route) {
        return isAnthropic(route) ? anthropicStreaming(properties, route) : openAiCompatibleStreaming(properties, route);
    }

    private static String reasoningEffort(AiProperties properties, AiProperties.Route route) {
        String reasoning = route.getReasoningEffort() == null ? properties.getEffort() : route.getReasoningEffort();
        return reasoning == null || reasoning.isBlank() ? null : reasoning;
    }

    /** Any OpenAI-compatible API (OpenAI, Groq, Gemini, OpenRouter, Mistral, a local Ollama…). */
    private static ChatModel openAiCompatible(AiProperties properties, AiProperties.Route route) {
        return OpenAiChatModel.builder()
                .baseUrl(blankToNull(route.getBaseUrl())) // blank = the provider's default (OpenAI)
                .apiKey(route.getApiKey())
                .modelName(route.getModel())
                .maxTokens(properties.getMaxTokens())
                .reasoningEffort(reasoningEffort(properties, route))
                .timeout(properties.getRequestTimeout())
                .maxRetries(properties.getMaxRetriesPerRoute())
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogRequests())
                .build();
    }

    private static StreamingChatModel openAiCompatibleStreaming(AiProperties properties, AiProperties.Route route) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(blankToNull(route.getBaseUrl())) // blank = the provider's default (OpenAI)
                .apiKey(route.getApiKey())
                .modelName(route.getModel())
                .maxTokens(properties.getMaxTokens())
                .reasoningEffort(reasoningEffort(properties, route))
                .timeout(properties.getRequestTimeout())
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogRequests())
                .build();
    }

    /** Claude through LangChain4j, with adaptive thinking and prompt caching. */
    private static ChatModel anthropic(AiProperties properties, AiProperties.Route route) {
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .apiKey(route.getApiKey())
                .modelName(route.getModel())
                .maxTokens(properties.getMaxTokens())
                .timeout(properties.getRequestTimeout())
                .maxRetries(properties.getMaxRetriesPerRoute())
                .thinkingType("adaptive")
                .returnThinking(false)
                .cacheTools(true)
                .cacheSystemMessages(true)
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogRequests());
        if (route.isServerSideFallback()) {
            builder.beta(SERVER_SIDE_FALLBACK_BETA);
        }
        if (route.getBaseUrl() != null && !route.getBaseUrl().isBlank()) {
            builder.baseUrl(route.getBaseUrl());
        }
        return builder.customParameters(anthropicParameters(properties, route)).build();
    }

    private static StreamingChatModel anthropicStreaming(AiProperties properties, AiProperties.Route route) {
        AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder builder = AnthropicStreamingChatModel.builder()
                .apiKey(route.getApiKey())
                .modelName(route.getModel())
                .maxTokens(properties.getMaxTokens())
                .timeout(properties.getRequestTimeout())
                .thinkingType("adaptive")
                .returnThinking(false)
                .cacheTools(true)
                .cacheSystemMessages(true)
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogRequests());
        if (route.isServerSideFallback()) {
            builder.beta(SERVER_SIDE_FALLBACK_BETA);
        }
        if (route.getBaseUrl() != null && !route.getBaseUrl().isBlank()) {
            builder.baseUrl(route.getBaseUrl());
        }
        return builder.customParameters(anthropicParameters(properties, route)).build();
    }

    private static Map<String, Object> anthropicParameters(AiProperties properties, AiProperties.Route route) {
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("output_config", Map.of("effort", properties.getEffort()));
        if (route.isServerSideFallback()) {
            custom.put("fallbacks", "default");
        }
        return custom;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static AiProperties bind(ConditionContext context) {
        return Binder.get(context.getEnvironment()).bind("argos.ai", AiProperties.class).orElseGet(AiProperties::new);
    }

    static final class EmbeddingConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return bind(context).embeddingConfigured();
        }
    }

    static final class RerankConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return bind(context).rerankConfigured();
        }
    }
}
