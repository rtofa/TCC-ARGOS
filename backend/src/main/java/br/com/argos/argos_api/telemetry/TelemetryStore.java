package br.com.argos.argos_api.telemetry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Writes telemetry in batches. Plain JDBC: the volume is high and the rows are immutable. */
@Component
class TelemetryStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    TelemetryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void saveSpans(UUID organizationId, List<ParsedTelemetry.Span> spans, Instant receivedAt) {
        if (spans.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO telemetry_spans (organization_id, trace_id, span_id, parent_span_id, service, name, kind,
                    start_time, duration_ns, status_code, status_message, attributes, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (organization_id, trace_id, span_id) DO NOTHING
                """, spans, spans.size(), (ps, span) -> {
            ps.setObject(1, organizationId);
            ps.setString(2, span.traceId());
            ps.setString(3, span.spanId());
            ps.setString(4, span.parentSpanId());
            ps.setString(5, span.service());
            ps.setString(6, span.name());
            ps.setInt(7, span.kind());
            ps.setTimestamp(8, Timestamp.from(span.startTime()));
            ps.setLong(9, span.durationNs());
            ps.setInt(10, span.statusCode());
            ps.setString(11, span.statusMessage());
            ps.setString(12, json(span.attributes()));
            ps.setTimestamp(13, Timestamp.from(receivedAt));
        });
    }

    void saveLogs(UUID organizationId, List<ParsedTelemetry.Log> logs, Instant receivedAt) {
        if (logs.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO telemetry_logs (organization_id, occurred_at, service, severity_number, severity_text, body,
                    trace_id, span_id, attributes, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, logs, logs.size(), (ps, log) -> {
            ps.setObject(1, organizationId);
            ps.setTimestamp(2, Timestamp.from(log.occurredAt()));
            ps.setString(3, log.service());
            ps.setInt(4, log.severityNumber());
            ps.setString(5, log.severityText());
            ps.setString(6, log.body());
            ps.setString(7, log.traceId());
            ps.setString(8, log.spanId());
            ps.setString(9, json(log.attributes()));
            ps.setTimestamp(10, Timestamp.from(receivedAt));
        });
    }

    void saveMetrics(UUID organizationId, List<ParsedTelemetry.Metric> metrics, Instant receivedAt) {
        if (metrics.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO telemetry_metrics (organization_id, occurred_at, service, name, kind, unit, value,
                    attributes, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, metrics, metrics.size(), (ps, metric) -> {
            ps.setObject(1, organizationId);
            ps.setTimestamp(2, Timestamp.from(metric.occurredAt()));
            ps.setString(3, metric.service());
            ps.setString(4, metric.name());
            ps.setString(5, metric.kind());
            ps.setString(6, metric.unit());
            ps.setDouble(7, metric.value());
            ps.setString(8, json(metric.attributes()));
            ps.setTimestamp(9, Timestamp.from(receivedAt));
        });
    }

    private static String json(Map<String, Object> attributes) {
        return JSON.writeValueAsString(attributes);
    }
}
