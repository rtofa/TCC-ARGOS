package br.com.argos.argos_api.telemetry;

import br.com.argos.argos_api.alerting.AlertProcessor;
import br.com.argos.argos_api.apikey.ApiKeyPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Evaluates the recent OTLP span stream of each touched service and automatically opens or resolves
 * incidents through {@link AlertProcessor} when the error rate crosses the threshold.
 */
@Component
class TelemetryMonitor {

    static final String SOURCE_NAME = "Monitor OTLP";
    static final Duration WINDOW = Duration.ofSeconds(90);
    static final long MIN_ERROR_SPANS = 3;
    static final long MIN_RECOVERY_SPANS = 3;
    static final double ERROR_RATE_THRESHOLD = 0.001;

    /** Load-generators and collector self-telemetry are not application services. */
    private static final Set<String> IGNORED_SERVICES = Set.of(
            "load-generator", "otelcol-contrib", "flagd", "flagd-ui", "telemetry-docs", OtlpParser.UNKNOWN_SERVICE);

    private final JdbcTemplate jdbc;
    private final AlertProcessor alertProcessor;
    private final Clock clock;

    TelemetryMonitor(JdbcTemplate jdbc, AlertProcessor alertProcessor, Clock clock) {
        this.jdbc = jdbc;
        this.alertProcessor = alertProcessor;
        this.clock = clock;
    }

    void evaluate(ApiKeyPrincipal key, Set<String> touchedServices) {
        Instant now = clock.instant();
        Timestamp since = Timestamp.from(now.minus(WINDOW));
        for (String service : touchedServices) {
            if (service == null || IGNORED_SERVICES.contains(service)) {
                continue;
            }
            evaluateService(key, service, since);
        }
    }

    private void evaluateService(ApiKeyPrincipal key, String service, Timestamp since) {
        // Ignore feature-flag polling noise (flagd client stream/evaluation spans).
        Stats stats = jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status_code = 2) AS errors,
                       max(name) FILTER (WHERE status_code = 2) AS sample_operation,
                       max(status_message) FILTER (WHERE status_code = 2 AND status_message IS NOT NULL) AS sample_message
                FROM telemetry_spans
                WHERE organization_id = ?
                  AND service = ?
                  AND start_time >= ?
                  AND name NOT LIKE 'flagd.evaluation.%'
                """, (rs, row) -> new Stats(
                rs.getLong("total"),
                rs.getLong("errors"),
                rs.getString("sample_operation"),
                rs.getString("sample_message")), key.organizationId(), service, since);

        if (stats == null || stats.total() == 0) {
            return;
        }
        String dedupKey = "otel-error-rate:" + service;
        double rate = (double) stats.errors() / stats.total();

        if (stats.errors() >= MIN_ERROR_SPANS && rate >= ERROR_RATE_THRESHOLD) {
            int pct = Math.max(1, (int) Math.round(rate * 100));
            String severity = (rate >= 0.30 || stats.errors() >= 25) ? "SEV1"
                    : (rate >= 0.10 || stats.errors() >= 10) ? "SEV2" : "SEV3";
            String title = "Erros detectados via OpenTelemetry em " + service + " (" + stats.errors() + " falhas, " + pct + "%)";
            String description = "O monitor automático OTLP detectou " + stats.errors() + " spans com erro de "
                    + stats.total() + " requisições (" + pct + "%) nos últimos " + WINDOW.toSeconds() + "s no serviço '"
                    + service + "'"
                    + (stats.sampleOperation() != null ? " (operação: " + stats.sampleOperation() + ")" : "")
                    + (stats.sampleMessage() != null ? " — " + stats.sampleMessage() : "") + ".";
            alertProcessor.reportSignal(key, SOURCE_NAME, dedupKey, true, title, description, severity, List.of(service));
        } else if (stats.errors() == 0 && stats.total() >= MIN_RECOVERY_SPANS) {
            alertProcessor.reportSignal(key, SOURCE_NAME, dedupKey, false,
                    "Serviço " + service + " normalizado", null, "SEV3", List.of(service));
        }
    }

    private record Stats(long total, long errors, String sampleOperation, String sampleMessage) {
    }
}
