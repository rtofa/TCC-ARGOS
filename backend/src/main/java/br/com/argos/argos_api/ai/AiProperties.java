package br.com.argos.argos_api.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings of the AI investigator ({@code argos.ai.*}). Each route carries its own provider, endpoint and key, so
 * the fallback chain can mix providers; routes without a key are skipped, and with no route left the module keeps
 * working in heuristic mode.
 */
@ConfigurationProperties(prefix = "argos.ai")
@Getter
@Setter
public class AiProperties {

    public static final String OPENAI_COMPATIBLE = "openai";
    public static final String ANTHROPIC = "anthropic";

    private boolean enabled = true;
    /** Off in tests: no real model is ever called, whatever keys the machine has. */
    private boolean llmEnabled = true;

    /** Ordered fallback chain: the router tries each model in turn when the previous one fails or is unhealthy. */
    private List<Route> routes = new ArrayList<>();

    private String investigationEffort = "medium";
    private String assistantEffort = "low";
    /** Kept modest: free tiers count requested output tokens against their per-minute limits. */
    private int maxTokens = 4096;
    private Duration requestTimeout = Duration.ofSeconds(90);
    /** Retries inside one model's client; the router handles rate limits and failover itself. */
    private int maxRetriesPerRoute = 0;
    /**
     * How long one model call may wait for a per-minute quota (free tiers) before the router tries the next model.
     * Investigations run in the background, so they wait and keep the best model; the assistant waits less.
     */
    private Duration investigationRateLimitWait = Duration.ofSeconds(45);
    private Duration assistantRateLimitWait = Duration.ofSeconds(15);
    private boolean logRequests = false;

    /** Tool calls one investigation may make; past this the tools ask the agent to conclude. */
    private int maxToolCalls = 8;
    /** Times the report guardrail may send the agent back to fix its answer. */
    private int maxOutputReprompts = 2;

    private boolean autoInvestigate = true;
    /** Waits a little after an incident opens so the alert burst and telemetry are in when the agent looks. */
    private Duration autoInvestigateDelay = Duration.ofSeconds(20);
    /** Generous because waiting for free-tier quotas is part of a normal run. */
    private Duration investigationTimeout = Duration.ofMinutes(10);
    private int maxAttempts = 2;
    private int workerThreads = 2;
    private int maxRunningPerOrganization = 1;

    private int circuitFailureThreshold = 3;
    private Duration circuitOpenDuration = Duration.ofSeconds(60);

    private int assistantThreads = 4;
    private int chatMemoryMaxMessages = 16;
    private Duration assistantTimeout = Duration.ofMinutes(3);

    /** Routes that can actually be called (enabled and with a key). */
    public List<Route> activeRoutes() {
        if (!enabled || !llmEnabled) {
            return List.of();
        }
        return routes.stream().filter(Route::hasKey).toList();
    }

    public boolean llmConfigured() {
        return !activeRoutes().isEmpty();
    }

    @Getter
    @Setter
    public static class Route {
        private String name;
        /** "openai" (any OpenAI-compatible API: Groq, Gemini, OpenRouter…) or "anthropic". */
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

        public boolean free() {
            return inputPricePerMillion.signum() == 0 && outputPricePerMillion.signum() == 0;
        }
    }
}
