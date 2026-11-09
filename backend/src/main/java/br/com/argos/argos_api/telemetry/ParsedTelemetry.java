package br.com.argos.argos_api.telemetry;

import java.time.Instant;
import java.util.Map;

/** Telemetry already parsed from OTLP and normalized, ready to be stored for one organization. */
final class ParsedTelemetry {

    private ParsedTelemetry() {
    }

    record Span(String traceId, String spanId, String parentSpanId, String service, String name, int kind,
                Instant startTime, long durationNs, int statusCode, String statusMessage,
                Map<String, Object> attributes) {
    }

    record Log(Instant occurredAt, String service, int severityNumber, String severityText, String body,
               String traceId, String spanId, Map<String, Object> attributes) {
    }

    record Metric(Instant occurredAt, String service, String name, String kind, String unit, double value,
                  Map<String, Object> attributes) {
    }

    /** Parsed records plus how many were dropped for being malformed. */
    record Batch<T>(java.util.List<T> accepted, int rejected) {
    }
}
