package br.com.argos.argos_api.ai.llm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Records every model call made on the current thread while an agent runs, so the investigation can report which
 * models answered, whether a fallback was used, tokens (including prompt-cache hits) and cost.
 *
 * <p>Thread-confined on purpose: the agent loop and its tools run on the worker thread that started the log.
 */
public final class LlmCallLog {

    public enum Outcome { OK, FAILED, SKIPPED, REFUSED, RATE_LIMITED }

    public record LlmCall(String route, String model, Outcome outcome, String error, long durationMs,
                          int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens,
                          BigDecimal costUsd) {
    }

    private static final ThreadLocal<List<LlmCall>> CURRENT = new ThreadLocal<>();

    private LlmCallLog() {
    }

    public static void start() {
        CURRENT.set(new ArrayList<>());
    }

    /** Ends the log on this thread and returns what was recorded. */
    public static List<LlmCall> stop() {
        List<LlmCall> calls = CURRENT.get();
        CURRENT.remove();
        return calls == null ? List.of() : List.copyOf(calls);
    }

    static void record(LlmCall call) {
        List<LlmCall> calls = CURRENT.get();
        if (calls != null) {
            calls.add(call);
        }
    }
}
