package br.com.argos.argos_api.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read models returned by the telemetry query API. */
public final class TelemetryViews {

    private TelemetryViews() {
    }

    public record ServiceSummary(String service, long spans, long errorSpans, long logs, long errorLogs,
                                 Instant lastSeen) {
    }

    public record TraceSummary(String traceId, Instant startTime, double durationMs, String rootService,
                               String rootName, long spanCount, long errorCount, List<String> services) {
    }

    public record SpanView(String traceId, String spanId, String parentSpanId, String service, String name, int kind,
                           Instant startTime, double durationMs, boolean error, String statusMessage,
                           Map<String, Object> attributes) {
    }

    public record TraceDetail(String traceId, Instant startTime, double durationMs, List<SpanView> spans) {
    }

    public record LogView(Instant occurredAt, String service, int severityNumber, String severityText, String body,
                          String traceId, String spanId, Map<String, Object> attributes) {
    }

    public record MetricSeries(String name, String service, String unit, long points, Instant lastSeen) {
    }

    public record MetricPoint(Instant occurredAt, String service, double value, Map<String, Object> attributes) {
    }
}
