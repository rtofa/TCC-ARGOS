package br.com.argos.argos_api.ai.agent;

import br.com.argos.argos_api.ai.agent.AgentContext.GuardrailEvent;
import br.com.argos.argos_api.ai.agent.AgentContext.Step;
import br.com.argos.argos_api.ai.llm.LlmCallLog.LlmCall;
import br.com.argos.argos_api.ai.llm.LlmCallLog.Outcome;

import java.math.BigDecimal;
import java.util.List;

/** Result of one investigation run, ready to be stored. {@code mode} is "AI" or "HEURISTIC". */
public record InvestigationOutcome(String mode, InvestigationReport report, List<Step> steps,
                                   List<GuardrailEvent> guardrailEvents, List<LlmCall> llmCalls, int toolCalls,
                                   String note) {

    /** Model that produced the last successful answer. */
    public String model() {
        return llmCalls.stream().filter(call -> call.outcome() == Outcome.OK).reduce((a, b) -> b)
                .map(LlmCall::model).orElse(null);
    }

    /** True when any call had to leave a model (failure, open circuit or refusal); waiting for a quota is not. */
    public boolean fallbackUsed() {
        return llmCalls.stream().anyMatch(call -> call.outcome() == Outcome.FAILED
                || call.outcome() == Outcome.SKIPPED || call.outcome() == Outcome.REFUSED);
    }

    public int inputTokens() {
        return llmCalls.stream().mapToInt(LlmCall::inputTokens).sum();
    }

    public int outputTokens() {
        return llmCalls.stream().mapToInt(LlmCall::outputTokens).sum();
    }

    public int cacheReadTokens() {
        return llmCalls.stream().mapToInt(LlmCall::cacheReadTokens).sum();
    }

    public int cacheWriteTokens() {
        return llmCalls.stream().mapToInt(LlmCall::cacheWriteTokens).sum();
    }

    public BigDecimal costUsd() {
        return llmCalls.stream().map(LlmCall::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
