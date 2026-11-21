package br.com.argos.argos_api.helpdesk.observability;

import org.slf4j.MDC;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Carries the logging context (traceId…) into tasks that run on other (virtual) threads, so every log line of one
 * execution can be correlated.
 */
public final class MdcPropagation {

    public static final String TRACE_ID = "traceId";

    private MdcPropagation() {
    }

    public static Runnable wrap(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            apply(context);
            try {
                task.run();
            } finally {
                apply(previous);
            }
        };
    }

    public static <T> Supplier<T> wrap(Supplier<T> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            apply(context);
            try {
                return task.get();
            } finally {
                apply(previous);
            }
        };
    }

    private static void apply(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
