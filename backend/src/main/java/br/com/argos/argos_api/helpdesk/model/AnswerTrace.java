package br.com.argos.argos_api.helpdesk.model;

import java.util.List;

/**
 * How an answer was produced: every stage with its timing and the model calls (including failovers), so a wrong
 * answer can be traced back to retrieval or generation.
 */
public record AnswerTrace(String traceId, long totalMs, List<TraceSpan> spans, List<ModelCall> modelCalls,
                          boolean fallbackUsed, int inputTokens, int outputTokens) {

    public record ModelCall(String route, String model, String outcome, long durationMs, int inputTokens,
                            int outputTokens, String error) {
    }
}
