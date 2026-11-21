package br.com.argos.argos_api.ai;

import br.com.argos.argos_api.shared.resilience.RetryPolicy;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings of the AI model layer ({@code argos.ai.*}), all overridable by environment variables.
 *
 * <ul>
 *   <li>{@link #routes}: the chat models, a primary and its fallbacks, each with its own provider, endpoint and key.
 *       Providers and models are not fixed in code: a route without model or key is simply left out.</li>
 *   <li>{@link #embedding}: the embedding model (OpenAI {@code text-embedding-3-small} by default).</li>
 *   <li>{@link #rerank}: the reranker (Cohere Rerank by default).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "argos.ai")
@Getter
@Setter
public class AiProperties {

    public static final String OPENAI_COMPATIBLE = "openai";
    public static final String ANTHROPIC = "anthropic";

    private boolean enabled = true;
    /**
     * Master switch for calls to external AI APIs (chat, embeddings, rerank). Off in tests: no real model is ever
     * called, whatever keys the machine has.
     */
    private boolean llmEnabled = true;

    /** Ordered fallback chain: the router tries each model in turn when the previous one fails or is unhealthy. */
    private List<Route> routes = new ArrayList<>();

    /** Reasoning effort sent to models that accept it (low, medium, high). */
    private String effort = "low";
    /** Kept modest: free tiers count requested output tokens against their per-minute limits. */
    private int maxTokens = 2048;
    /** Per-request timeout of each chat model (connect + read). */
    private Duration requestTimeout = Duration.ofSeconds(45);
    /** Retries inside one model's HTTP client; the router does its own retries with backoff. */
    private int maxRetriesPerRoute = 0;
    /** How long one call may wait for a per-minute quota (free tiers) before the router tries the next model. */
    private Duration rateLimitWait = Duration.ofSeconds(8);
    private boolean logRequests = false;

    /** Retries with backoff for transient failures (timeout, 429, 5xx), before failing over to the next model. */
    private Retry retry = new Retry();

    private int circuitFailureThreshold = 3;
    private Duration circuitOpenDuration = Duration.ofSeconds(60);

    private Embedding embedding = new Embedding();
    private Rerank rerank = new Rerank();

    /** Routes that can actually be called (switched on, with a model and a key). */
    public List<Route> activeRoutes() {
        if (!enabled || !llmEnabled) {
            return List.of();
        }
        return routes.stream().filter(Route::usable).toList();
    }

    public boolean llmConfigured() {
        return !activeRoutes().isEmpty();
    }

    public boolean embeddingConfigured() {
        return enabled && llmEnabled && embedding.usable();
    }

    public boolean rerankConfigured() {
        return enabled && llmEnabled && rerank.usable();
    }

    @Getter
    @Setter
    public static class Route {
        private String name;
        /** "openai" (any OpenAI-compatible API: OpenAI, Groq, Gemini, OpenRouter, Mistral, Ollama…) or "anthropic". */
        private String provider = OPENAI_COMPATIBLE;
        private String baseUrl;
        private String apiKey = "";
        private String model;
        /** Overrides the router's reasoning effort for models with their own vocabulary; blank = not sent. */
        private String reasoningEffort;
        private BigDecimal inputPricePerMillion = BigDecimal.ZERO;
        private BigDecimal outputPricePerMillion = BigDecimal.ZERO;
        private BigDecimal cacheReadPricePerMillion = BigDecimal.ZERO;
        /** Anthropic only: server-side refusal fallback ({@code fallbacks: "default"}). */
        private boolean serverSideFallback = false;

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public boolean usable() {
            return hasKey() && model != null && !model.isBlank();
        }

        public boolean free() {
            return inputPricePerMillion.signum() == 0 && outputPricePerMillion.signum() == 0;
        }
    }

    @Getter
    @Setter
    public static class Retry {
        /** Total attempts per model, the first one included. */
        private int maxAttempts = 2;
        private Duration initialDelay = Duration.ofMillis(400);
        private Duration maxDelay = Duration.ofSeconds(4);

        public RetryPolicy toPolicy() {
            return RetryPolicy.exponential(Math.max(1, maxAttempts), initialDelay, maxDelay);
        }
    }

    /** Embedding model behind any OpenAI-compatible {@code /embeddings} endpoint. */
    @Getter
    @Setter
    public static class Embedding {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "text-embedding-3-small";
        /** Vector size stored in the search index; changing it rebuilds the index. */
        private int dimensions = 1536;
        private Duration timeout = Duration.ofSeconds(20);
        /** Texts per request when indexing documents. */
        private int batchSize = 64;

        public boolean usable() {
            return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
        }
    }

    /** Cross-encoder reranker (Cohere Rerank). */
    @Getter
    @Setter
    public static class Rerank {
        /** Blank = Cohere's default endpoint. */
        private String baseUrl;
        private String apiKey = "";
        private String model = "rerank-v3.5";
        private Duration timeout = Duration.ofSeconds(10);

        public boolean usable() {
            return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
        }
    }
}
