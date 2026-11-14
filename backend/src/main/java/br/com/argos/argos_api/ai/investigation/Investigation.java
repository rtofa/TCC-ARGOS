package br.com.argos.argos_api.ai.investigation;

import br.com.argos.argos_api.ai.agent.AgentContext.GuardrailEvent;
import br.com.argos.argos_api.ai.agent.AgentContext.Step;
import br.com.argos.argos_api.ai.agent.InvestigationReport;
import br.com.argos.argos_api.ai.llm.LlmCallLog.LlmCall;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One AI investigation of an incident, which is also an item of the work queue. */
public record Investigation(UUID id, UUID organizationId, UUID incidentId, String status, String trigger, String mode,
                            UUID requestedBy, int attempts, Instant runAfter, InvestigationReport report,
                            List<Step> steps, List<GuardrailEvent> guardrailEvents, List<LlmCall> llmCalls,
                            String model, boolean fallbackUsed, int inputTokens, int outputTokens, int cacheReadTokens,
                            int cacheWriteTokens, BigDecimal costUsd, int toolCalls, String error, Instant createdAt,
                            Instant startedAt, Instant finishedAt) {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    public boolean active() {
        return PENDING.equals(status) || RUNNING.equals(status);
    }

    /** A claimed queue item; {@code attempt} fences late writes from a run the watchdog already gave up on. */
    public record Claim(UUID id, UUID organizationId, UUID incidentId, int attempt) {
    }
}
