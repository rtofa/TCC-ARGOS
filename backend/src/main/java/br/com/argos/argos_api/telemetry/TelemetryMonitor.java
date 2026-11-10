package br.com.argos.argos_api.telemetry;

import br.com.argos.argos_api.alerting.AlertProcessor;
import br.com.argos.argos_api.apikey.ApiKeyPrincipal;
import br.com.argos.argos_api.shared.security.CurrentUser;
import br.com.argos.argos_api.telemetry.TelemetryViews.TelemetryRuleRequest;
import br.com.argos.argos_api.telemetry.TelemetryViews.TelemetryRuleView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Evaluates the recent OTLP span stream of each touched service against configurable SLO rules
 * (error rate % and latency p95 in ms) and automatically opens or resolves incidents through
 * {@link AlertProcessor}.
 */
@Component
class TelemetryMonitor {

    static final String SOURCE_NAME = "Monitor OTLP";
    static final int DEFAULT_WINDOW_SECONDS = 90;
    static final int DEFAULT_MIN_REQUESTS = 3;
    static final long MIN_RECOVERY_SPANS = 3;
    static final double DEFAULT_MAX_ERROR_RATE_PERCENT = 0.1;

    private static final Set<String> VALID_SEVERITIES = Set.of("SEV1", "SEV2", "SEV3", "SEV4");

    /** Load-generators and collector self-telemetry are not application services. */
    private static final Set<String> IGNORED_SERVICES = Set.of(
            "load-generator", "otelcol-contrib", "flagd", "flagd-ui", "telemetry-docs", OtlpParser.UNKNOWN_SERVICE);

    private final JdbcTemplate jdbc;
    private final AlertProcessor alertProcessor;
    private final CurrentUser currentUser;
    private final Clock clock;

    TelemetryMonitor(JdbcTemplate jdbc, AlertProcessor alertProcessor, CurrentUser currentUser, Clock clock) {
        this.jdbc = jdbc;
        this.alertProcessor = alertProcessor;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<TelemetryRuleView> listRules() {
        UUID org = currentUser.get().organizationId();
        return jdbc.query("""
                SELECT id, service, window_seconds, max_error_rate_percent, min_requests,
                       max_p95_latency_ms, severity, enabled, updated_at
                FROM telemetry_rules
                WHERE organization_id = ?
                ORDER BY CASE WHEN service = '*' THEN 0 ELSE 1 END, service
                """, (rs, row) -> new TelemetryRuleView(
                rs.getObject("id", UUID.class),
                rs.getString("service"),
                rs.getInt("window_seconds"),
                (Double) rs.getObject("max_error_rate_percent"),
                rs.getInt("min_requests"),
                (Double) rs.getObject("max_p95_latency_ms"),
                rs.getString("severity"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("updated_at").toInstant()), org);
    }

    @Transactional
    TelemetryRuleView saveRule(TelemetryRuleRequest req) {
        ValidatedRule v = validate(req);
        UUID org = currentUser.get().organizationId();
        Instant now = clock.instant();
        UUID id = jdbc.queryForObject("""
                INSERT INTO telemetry_rules (organization_id, service, window_seconds, max_error_rate_percent,
                                             min_requests, max_p95_latency_ms, severity, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, service) DO UPDATE
                    SET window_seconds = EXCLUDED.window_seconds,
                        max_error_rate_percent = EXCLUDED.max_error_rate_percent,
                        min_requests = EXCLUDED.min_requests,
                        max_p95_latency_ms = EXCLUDED.max_p95_latency_ms,
                        severity = EXCLUDED.severity,
                        enabled = EXCLUDED.enabled,
                        updated_at = EXCLUDED.updated_at
                RETURNING id
                """, UUID.class,
                org, v.service(), v.windowSeconds(), v.maxErrorRatePercent(), v.minRequests(),
                v.maxP95LatencyMs(), v.severity(), v.enabled(), Timestamp.from(now), Timestamp.from(now));
        return new TelemetryRuleView(id, v.service(), v.windowSeconds(), v.maxErrorRatePercent(),
                v.minRequests(), v.maxP95LatencyMs(), v.severity(), v.enabled(), now);
    }

    @Transactional
    TelemetryRuleView updateRule(UUID id, TelemetryRuleRequest req) {
        ValidatedRule v = validate(req);
        UUID org = currentUser.get().organizationId();
        Instant now = clock.instant();
        int updated = jdbc.update("""
                UPDATE telemetry_rules
                SET service = ?, window_seconds = ?, max_error_rate_percent = ?, min_requests = ?,
                    max_p95_latency_ms = ?, severity = ?, enabled = ?, updated_at = ?
                WHERE id = ? AND organization_id = ?
                """,
                v.service(), v.windowSeconds(), v.maxErrorRatePercent(), v.minRequests(),
                v.maxP95LatencyMs(), v.severity(), v.enabled(), Timestamp.from(now), id, org);
        if (updated == 0) {
            throw new IllegalArgumentException("Regra de monitoramento não encontrada");
        }
        return new TelemetryRuleView(id, v.service(), v.windowSeconds(), v.maxErrorRatePercent(),
                v.minRequests(), v.maxP95LatencyMs(), v.severity(), v.enabled(), now);
    }

    @Transactional
    void deleteRule(UUID id) {
        UUID org = currentUser.get().organizationId();
        jdbc.update("DELETE FROM telemetry_rules WHERE id = ? AND organization_id = ?", id, org);
    }

    void evaluate(ApiKeyPrincipal key, Set<String> touchedServices) {
        Instant now = clock.instant();
        Map<String, RuleConfig> rulesByService = loadRules(key.organizationId());
        for (String service : touchedServices) {
            if (service == null || IGNORED_SERVICES.contains(service)) {
                continue;
            }
            RuleConfig rule = rulesByService.getOrDefault(service,
                    rulesByService.getOrDefault("*", RuleConfig.DEFAULT));
            if (!rule.enabled()) {
                continue;
            }
            Timestamp since = Timestamp.from(now.minus(Duration.ofSeconds(rule.windowSeconds())));
            evaluateService(key, service, since, rule);
        }
    }

    private Map<String, RuleConfig> loadRules(UUID organizationId) {
        Map<String, RuleConfig> map = new HashMap<>();
        jdbc.query("""
                SELECT service, window_seconds, max_error_rate_percent, min_requests,
                       max_p95_latency_ms, severity, enabled
                FROM telemetry_rules
                WHERE organization_id = ?
                """, rs -> {
            map.put(rs.getString("service"), new RuleConfig(
                    rs.getInt("window_seconds"),
                    (Double) rs.getObject("max_error_rate_percent"),
                    rs.getInt("min_requests"),
                    (Double) rs.getObject("max_p95_latency_ms"),
                    rs.getString("severity"),
                    rs.getBoolean("enabled"),
                    true));
        }, organizationId);
        return map;
    }

    private void evaluateService(ApiKeyPrincipal key, String service, Timestamp since, RuleConfig rule) {
        // Ignore feature-flag polling noise (flagd client stream/evaluation spans).
        Stats stats = jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status_code = 2) AS errors,
                       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ns) / 1e6, 0) AS p95_ms,
                       max(name) FILTER (WHERE status_code = 2) AS sample_operation,
                       max(status_message) FILTER (WHERE status_code = 2 AND status_message IS NOT NULL) AS sample_message,
                       (array_agg(name ORDER BY duration_ns DESC))[1] AS slowest_operation
                FROM telemetry_spans
                WHERE organization_id = ?
                  AND service = ?
                  AND start_time >= ?
                  AND name NOT LIKE 'flagd.evaluation.%'
                """, (rs, row) -> new Stats(
                rs.getLong("total"),
                rs.getLong("errors"),
                rs.getDouble("p95_ms"),
                rs.getString("sample_operation"),
                rs.getString("sample_message"),
                rs.getString("slowest_operation")), key.organizationId(), service, since);

        if (stats == null || stats.total() == 0) {
            return;
        }

        // 1. Error rate evaluation
        if (rule.maxErrorRatePercent() != null) {
            String errorDedupKey = "otel-error-rate:" + service;
            double ratePercent = (stats.errors() * 100.0) / stats.total();
            if (stats.errors() >= rule.minRequests() && ratePercent >= rule.maxErrorRatePercent()) {
                int pct = Math.max(1, (int) Math.round(ratePercent));
                String severity = rule.custom() ? rule.severity()
                        : (ratePercent >= 30.0 || stats.errors() >= 25) ? "SEV1"
                        : (ratePercent >= 10.0 || stats.errors() >= 10) ? "SEV2" : "SEV3";
                String title = "Erros detectados via OpenTelemetry em " + service + " (" + stats.errors() + " falhas, " + pct + "%)";
                String description = "O monitor automático OTLP detectou " + stats.errors() + " spans com erro de "
                        + stats.total() + " requisições (" + pct + "%) nos últimos " + rule.windowSeconds() + "s no serviço '"
                        + service + "'"
                        + (stats.sampleOperation() != null ? " (operação: " + stats.sampleOperation() + ")" : "")
                        + (stats.sampleMessage() != null ? " — " + stats.sampleMessage() : "") + ".";
                alertProcessor.reportSignal(key, SOURCE_NAME, errorDedupKey, true, title, description, severity, List.of(service));
            } else if (stats.errors() == 0 && stats.total() >= MIN_RECOVERY_SPANS) {
                alertProcessor.reportSignal(key, SOURCE_NAME, errorDedupKey, false,
                        "Serviço " + service + " normalizado", null, "SEV3", List.of(service));
            }
        }

        // 2. Latency p95 SLO evaluation
        if (rule.maxP95LatencyMs() != null && rule.maxP95LatencyMs() > 0) {
            String latencyDedupKey = "otel-latency-p95:" + service;
            if (stats.total() >= rule.minRequests() && stats.p95Ms() > rule.maxP95LatencyMs()) {
                long p95Rounded = Math.round(stats.p95Ms());
                long limitRounded = Math.round(rule.maxP95LatencyMs());
                String title = "Degradação de latência p95 em " + service + " (" + p95Rounded + " ms > " + limitRounded + " ms)";
                String description = "O monitor de SLO OpenTelemetry detectou latência p95 de " + p95Rounded
                        + " ms (limite configurado: " + limitRounded + " ms) em " + stats.total()
                        + " requisições nos últimos " + rule.windowSeconds() + "s no serviço '" + service + "'"
                        + (stats.slowestOperation() != null ? " (operação mais lenta: " + stats.slowestOperation() + ")" : "") + ".";
                alertProcessor.reportSignal(key, SOURCE_NAME, latencyDedupKey, true, title, description, rule.severity(), List.of(service));
            } else if (stats.total() >= MIN_RECOVERY_SPANS && stats.p95Ms() <= rule.maxP95LatencyMs()) {
                alertProcessor.reportSignal(key, SOURCE_NAME, latencyDedupKey, false,
                        "Latência de " + service + " normalizada", null, "SEV3", List.of(service));
            }
        }
    }

    private static ValidatedRule validate(TelemetryRuleRequest req) {
        if (req == null || req.service() == null || req.service().isBlank()) {
            throw new IllegalArgumentException("service é obrigatório");
        }
        String service = req.service().trim();
        int windowSeconds = req.windowSeconds() == null ? DEFAULT_WINDOW_SECONDS : req.windowSeconds();
        if (windowSeconds < 30 || windowSeconds > 3600) {
            throw new IllegalArgumentException("windowSeconds deve estar entre 30 e 3600");
        }
        int minRequests = req.minRequests() == null ? DEFAULT_MIN_REQUESTS : req.minRequests();
        if (minRequests < 1 || minRequests > 10000) {
            throw new IllegalArgumentException("minRequests deve ser maior ou igual a 1");
        }
        Double maxErrorRate = req.maxErrorRatePercent();
        if (maxErrorRate != null && (maxErrorRate <= 0 || maxErrorRate > 100)) {
            throw new IllegalArgumentException("maxErrorRatePercent deve estar entre 0 e 100");
        }
        Double maxP95 = req.maxP95LatencyMs();
        if (maxP95 != null && maxP95 <= 0) {
            throw new IllegalArgumentException("maxP95LatencyMs deve ser maior que zero");
        }
        if (maxErrorRate == null && maxP95 == null) {
            throw new IllegalArgumentException("Informe ao menos um limite (taxa de erro ou latência p95)");
        }
        String severity = req.severity() == null || req.severity().isBlank()
                ? "SEV2"
                : req.severity().trim().toUpperCase(Locale.ROOT);
        if (!VALID_SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException("severity deve ser SEV1, SEV2, SEV3 ou SEV4");
        }
        boolean enabled = req.enabled() == null || req.enabled();
        return new ValidatedRule(service, windowSeconds, maxErrorRate, minRequests, maxP95, severity, enabled);
    }

    private record ValidatedRule(String service, int windowSeconds, Double maxErrorRatePercent, int minRequests,
                                 Double maxP95LatencyMs, String severity, boolean enabled) {
    }

    private record RuleConfig(int windowSeconds, Double maxErrorRatePercent, int minRequests, Double maxP95LatencyMs,
                              String severity, boolean enabled, boolean custom) {
        static final RuleConfig DEFAULT = new RuleConfig(
                DEFAULT_WINDOW_SECONDS, DEFAULT_MAX_ERROR_RATE_PERCENT, DEFAULT_MIN_REQUESTS, null, "SEV2", true, false);
    }

    private record Stats(long total, long errors, double p95Ms, String sampleOperation, String sampleMessage,
                         String slowestOperation) {
    }
}
