package br.com.argos.argos_api.helpdesk.observability;

import br.com.argos.argos_api.helpdesk.model.TraceSpan;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracing of one support-agent execution. Every stage is a span with its timing, status and attributes:
 *
 * <ul>
 *   <li>returned to the client inside the answer (so the UI and the tests can see how it was produced);</li>
 *   <li>logged as structured key-value events with the {@code traceId};</li>
 *   <li>reported as a Micrometer {@link Observation}, which becomes a span/metric as soon as a tracer or meter
 *       registry is wired in (OpenTelemetry, Prometheus…).</li>
 * </ul>
 *
 * Thread-safe: retrievers record their spans from parallel virtual threads.
 */
public final class ExecutionTrace {

    public static final String OBSERVATION_NAME = "argos.helpdesk.stage";
    private static final Logger log = LoggerFactory.getLogger(ExecutionTrace.class);

    private final String traceId;
    private final long startedNanos = System.nanoTime();
    private final ObservationRegistry registry;
    private final List<TraceSpan> spans = Collections.synchronizedList(new ArrayList<>());

    public ExecutionTrace(ObservationRegistry registry) {
        this(UUID.randomUUID().toString(), registry);
    }

    public ExecutionTrace(String traceId, ObservationRegistry registry) {
        this.traceId = traceId;
        this.registry = registry == null ? ObservationRegistry.NOOP : registry;
    }

    /** Starts a stage; close it (try-with-resources) to record it. */
    public Span start(String name) {
        return new Span(name);
    }

    public String traceId() {
        return traceId;
    }

    public long elapsedMs() {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    public List<TraceSpan> spans() {
        synchronized (spans) {
            return spans.stream().sorted((a, b) -> Long.compare(a.startMs(), b.startMs())).toList();
        }
    }

    public final class Span implements AutoCloseable {

        private final String name;
        private final long startMs;
        private final long startNanos = System.nanoTime();
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final Observation observation;
        private String status = "ok";
        private boolean closed;

        private Span(String name) {
            this.name = name;
            this.startMs = elapsedMs();
            this.observation = Observation.createNotStarted(OBSERVATION_NAME, registry)
                    .contextualName("helpdesk " + name)
                    .lowCardinalityKeyValue("stage", name)
                    .highCardinalityKeyValue("trace.id", traceId)
                    .start();
        }

        public Span attr(String key, Object value) {
            if (value != null) {
                attributes.put(key, value);
            }
            return this;
        }

        /** The stage failed; the execution may still go on in degraded mode. */
        public Span fail(Throwable error) {
            status = "error";
            attributes.put("error", error.getClass().getSimpleName() + ": " + summary(error.getMessage()));
            observation.error(error);
            return this;
        }

        /** The stage was not executed (capability off, nothing to do). */
        public Span skip(String reason) {
            status = "skipped";
            attributes.put("reason", reason);
            return this;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            spans.add(new TraceSpan(name, startMs, durationMs, status, Map.copyOf(attributes)));
            observation.lowCardinalityKeyValue("status", status);
            observation.stop();
            var event = log.atDebug().setMessage("helpdesk.stage")
                    .addKeyValue("traceId", traceId)
                    .addKeyValue("stage", name)
                    .addKeyValue("status", status)
                    .addKeyValue("durationMs", durationMs);
            for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
                event = event.addKeyValue(attribute.getKey(), attribute.getValue());
            }
            event.log();
        }
    }

    private static String summary(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }
}
