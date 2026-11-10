package br.com.argos.argos_api.telemetry;

import br.com.argos.argos_api.shared.security.CurrentUser;
import br.com.argos.argos_api.telemetry.TelemetryViews.LogView;
import br.com.argos.argos_api.telemetry.TelemetryViews.MetricPoint;
import br.com.argos.argos_api.telemetry.TelemetryViews.MetricSeries;
import br.com.argos.argos_api.telemetry.TelemetryViews.RedBucket;
import br.com.argos.argos_api.telemetry.TelemetryViews.RedSeries;
import br.com.argos.argos_api.telemetry.TelemetryViews.ServiceSummary;
import br.com.argos.argos_api.telemetry.TelemetryViews.ServiceTopology;
import br.com.argos.argos_api.telemetry.TelemetryViews.SpanView;
import br.com.argos.argos_api.telemetry.TelemetryViews.TopologyEdge;
import br.com.argos.argos_api.telemetry.TelemetryViews.TopologyNode;
import br.com.argos.argos_api.telemetry.TelemetryViews.TraceDetail;
import br.com.argos.argos_api.telemetry.TelemetryViews.TraceSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Every query is scoped to the organization of the authenticated user. */
@Service
@Transactional(readOnly = true)
public class TelemetryQueryService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_TRACES = 100;
    static final int MAX_LOGS = 500;
    static final int MAX_POINTS = 2_000;
    static final Duration DEFAULT_WINDOW = Duration.ofHours(1);
    static final int ERROR_SEVERITY = 17;
    static final int ERROR_STATUS = 2;

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Map<String, Integer> LEVELS = Map.of("TRACE", 1, "DEBUG", 5, "INFO", 9, "WARN", 13,
            "ERROR", ERROR_SEVERITY, "FATAL", 21);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final Clock clock;

    public TelemetryQueryService(JdbcTemplate jdbc, CurrentUser currentUser, Clock clock) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public List<ServiceSummary> services(Instant from, Instant to) {
        UUID org = currentUser.get().organizationId();
        Window window = window(from, to);
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, Instant> lastSeen = new LinkedHashMap<>();

        jdbc.query("""
                SELECT service, count(*) AS total, count(*) FILTER (WHERE status_code = ?) AS errors,
                       max(start_time) AS last_seen
                FROM telemetry_spans WHERE organization_id = ? AND start_time >= ? AND start_time <= ?
                GROUP BY service
                """, rs -> {
            long[] row = counts.computeIfAbsent(rs.getString("service"), k -> new long[4]);
            row[0] = rs.getLong("total");
            row[1] = rs.getLong("errors");
            lastSeen.merge(rs.getString("service"), rs.getTimestamp("last_seen").toInstant(), (a, b) -> a.isAfter(b) ? a : b);
        }, ERROR_STATUS, org, ts(window.from()), ts(window.to()));

        jdbc.query("""
                SELECT service, count(*) AS total, count(*) FILTER (WHERE severity_number >= ?) AS errors,
                       max(occurred_at) AS last_seen
                FROM telemetry_logs WHERE organization_id = ? AND occurred_at >= ? AND occurred_at <= ?
                GROUP BY service
                """, rs -> {
            long[] row = counts.computeIfAbsent(rs.getString("service"), k -> new long[4]);
            row[2] = rs.getLong("total");
            row[3] = rs.getLong("errors");
            lastSeen.merge(rs.getString("service"), rs.getTimestamp("last_seen").toInstant(), (a, b) -> a.isAfter(b) ? a : b);
        }, ERROR_SEVERITY, org, ts(window.from()), ts(window.to()));

        return counts.entrySet().stream()
                .map(e -> new ServiceSummary(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        e.getValue()[3], lastSeen.get(e.getKey())))
                .sorted(Comparator.comparingLong(ServiceSummary::errorSpans).reversed()
                        .thenComparing(ServiceSummary::service))
                .toList();
    }

    /** Traces having at least one span that matches the filters, newest first, summarized in full. */
    public List<TraceSummary> traces(String service, boolean onlyErrors, Long minDurationMs, Instant from, Instant to,
                                     Integer limit) {
        UUID org = currentUser.get().organizationId();
        Window window = window(from, to);
        List<Object> params = new ArrayList<>(List.of(org, ts(window.from()), ts(window.to())));
        StringBuilder filters = new StringBuilder();
        if (service != null && !service.isBlank()) {
            filters.append(" AND service = ?");
            params.add(service);
        }
        if (onlyErrors) {
            filters.append(" AND status_code = ?");
            params.add(ERROR_STATUS);
        }
        if (minDurationMs != null && minDurationMs > 0) {
            filters.append(" AND duration_ns >= ?");
            params.add(minDurationMs * 1_000_000L);
        }
        params.add(clamp(limit, MAX_TRACES));
        params.add(org);

        return jdbc.query("""
                WITH matched AS (
                    SELECT trace_id, max(start_time) AS last_start FROM telemetry_spans
                    WHERE organization_id = ? AND start_time >= ? AND start_time <= ?""" + filters + """

                    GROUP BY trace_id ORDER BY last_start DESC LIMIT ?)
                SELECT s.trace_id,
                       min(s.start_time) AS started,
                       max(s.start_time + make_interval(secs => s.duration_ns / 1e9)) AS ended,
                       (array_agg(s.service ORDER BY (s.parent_span_id IS NOT NULL), s.start_time))[1] AS root_service,
                       (array_agg(s.name ORDER BY (s.parent_span_id IS NOT NULL), s.start_time))[1] AS root_name,
                       count(*) AS spans,
                       count(*) FILTER (WHERE s.status_code = 2) AS errors,
                       array_agg(DISTINCT s.service) AS services
                FROM telemetry_spans s JOIN matched m ON m.trace_id = s.trace_id
                WHERE s.organization_id = ?
                GROUP BY s.trace_id, m.last_start ORDER BY m.last_start DESC
                """, (rs, row) -> {
            Instant started = rs.getTimestamp("started").toInstant();
            Instant ended = rs.getTimestamp("ended").toInstant();
            return new TraceSummary(rs.getString("trace_id"), started, millis(started, ended),
                    rs.getString("root_service"), rs.getString("root_name"), rs.getLong("spans"),
                    rs.getLong("errors"), strings(rs.getArray("services")));
        }, params.toArray());
    }

    public TraceDetail trace(String traceId) {
        if (traceId == null || !TRACE_ID.matcher(traceId).matches()) {
            throw new IllegalArgumentException("traceId deve ter 32 caracteres hexadecimais");
        }
        UUID org = currentUser.get().organizationId();
        List<SpanView> spans = jdbc.query("""
                SELECT trace_id, span_id, parent_span_id, service, name, kind, start_time, duration_ns, status_code,
                       status_message, attributes::text AS attributes
                FROM telemetry_spans WHERE organization_id = ? AND trace_id = ?
                ORDER BY start_time, span_id
                """, (rs, row) -> new SpanView(rs.getString("trace_id"), rs.getString("span_id"),
                rs.getString("parent_span_id"), rs.getString("service"), rs.getString("name"), rs.getInt("kind"),
                rs.getTimestamp("start_time").toInstant(), rs.getLong("duration_ns") / 1e6,
                rs.getInt("status_code") == ERROR_STATUS, rs.getString("status_message"),
                attributes(rs.getString("attributes"))), org, traceId.toLowerCase(Locale.ROOT));
        if (spans.isEmpty()) {
            throw new TraceNotFoundException(traceId);
        }
        Instant started = spans.stream().map(SpanView::startTime).min(Comparator.naturalOrder()).orElseThrow();
        Instant ended = spans.stream().map(s -> s.startTime().plusNanos((long) (s.durationMs() * 1e6)))
                .max(Comparator.naturalOrder()).orElseThrow();
        return new TraceDetail(spans.get(0).traceId(), started, millis(started, ended), spans);
    }

    /** {@code level} is the minimum severity (TRACE, DEBUG, INFO, WARN, ERROR, FATAL). */
    public List<LogView> logs(String service, String level, String traceId, String text, Instant from, Instant to,
                              Integer limit) {
        UUID org = currentUser.get().organizationId();
        Window window = window(from, to);
        List<Object> params = new ArrayList<>(List.of(org, ts(window.from()), ts(window.to())));
        StringBuilder filters = new StringBuilder();
        if (service != null && !service.isBlank()) {
            filters.append(" AND service = ?");
            params.add(service);
        }
        if (level != null && !level.isBlank()) {
            Integer minimum = LEVELS.get(level.toUpperCase(Locale.ROOT));
            if (minimum == null) {
                throw new IllegalArgumentException("level deve ser um de " + LEVELS.keySet());
            }
            filters.append(" AND severity_number >= ?");
            params.add(minimum);
        }
        if (traceId != null && !traceId.isBlank()) {
            if (!TRACE_ID.matcher(traceId).matches()) {
                throw new IllegalArgumentException("traceId deve ter 32 caracteres hexadecimais");
            }
            filters.append(" AND trace_id = ?");
            params.add(traceId.toLowerCase(Locale.ROOT));
        }
        if (text != null && !text.isBlank()) {
            filters.append(" AND position(lower(?) in lower(body)) > 0");
            params.add(text);
        }
        params.add(clamp(limit, MAX_LOGS));

        return jdbc.query("""
                SELECT occurred_at, service, severity_number, severity_text, body, trace_id, span_id,
                       attributes::text AS attributes
                FROM telemetry_logs WHERE organization_id = ? AND occurred_at >= ? AND occurred_at <= ?""" + filters + """

                ORDER BY occurred_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new LogView(rs.getTimestamp("occurred_at").toInstant(), rs.getString("service"),
                rs.getInt("severity_number"), rs.getString("severity_text"), rs.getString("body"),
                rs.getString("trace_id"), rs.getString("span_id"), attributes(rs.getString("attributes"))),
                params.toArray());
    }

    public List<MetricSeries> metricSeries(Instant from, Instant to) {
        UUID org = currentUser.get().organizationId();
        Window window = window(from, to);
        return jdbc.query("""
                SELECT name, service, max(unit) AS unit, count(*) AS points, max(occurred_at) AS last_seen
                FROM telemetry_metrics WHERE organization_id = ? AND occurred_at >= ? AND occurred_at <= ?
                GROUP BY name, service ORDER BY name, service
                """, (rs, row) -> new MetricSeries(rs.getString("name"), rs.getString("service"),
                rs.getString("unit"), rs.getLong("points"), rs.getTimestamp("last_seen").toInstant()),
                org, ts(window.from()), ts(window.to()));
    }

    /** Oldest first, so a chart can plot it as is; when over the limit the newest points are kept. */
    public List<MetricPoint> metricPoints(String name, String service, Instant from, Instant to, Integer limit) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name e obrigatorio");
        }
        UUID org = currentUser.get().organizationId();
        Window window = window(from, to);
        List<Object> params = new ArrayList<>(List.of(org, name, ts(window.from()), ts(window.to())));
        String serviceFilter = "";
        if (service != null && !service.isBlank()) {
            serviceFilter = " AND service = ?";
            params.add(service);
        }
        params.add(clamp(limit, MAX_POINTS));
        List<MetricPoint> points = jdbc.query("""
                SELECT occurred_at, service, value, attributes::text AS attributes FROM telemetry_metrics
                WHERE organization_id = ? AND name = ? AND occurred_at >= ? AND occurred_at <= ?""" + serviceFilter + """

                ORDER BY occurred_at DESC LIMIT ?
                """, (rs, row) -> new MetricPoint(rs.getTimestamp("occurred_at").toInstant(), rs.getString("service"),
                rs.getDouble("value"), attributes(rs.getString("attributes"))), params.toArray());
        List<MetricPoint> ascending = new ArrayList<>(points);
        java.util.Collections.reverse(ascending);
        return ascending;
    }

    /**
     * Builds the live service dependency graph from parent-child OTLP spans within each trace,
     * distinguishing root-cause failing services from upstream services suffering cascading errors.
     */
    public ServiceTopology topology(Instant from, Instant to) {
        UUID org = currentUser.get().organizationId();
        Window window = window(from, to);
        Timestamp start = ts(window.from());
        Timestamp end = ts(window.to());

        List<TopologyEdge> edges = jdbc.query("""
                SELECT p.service AS source,
                       c.service AS target,
                       count(*) AS calls,
                       count(*) FILTER (WHERE c.status_code = 2) AS errors,
                       COALESCE(avg(c.duration_ns) / 1e6, 0) AS avg_duration_ms,
                       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY c.duration_ns) / 1e6, 0) AS p95_duration_ms
                FROM telemetry_spans c
                JOIN telemetry_spans p
                  ON p.organization_id = c.organization_id
                 AND p.trace_id = c.trace_id
                 AND p.span_id = c.parent_span_id
                WHERE c.organization_id = ?
                  AND c.start_time >= ? AND c.start_time <= ?
                  AND p.service <> c.service
                  AND p.service NOT IN ('load-generator', 'otelcol-contrib', 'flagd', 'flagd-ui', 'telemetry-docs', 'unknown_service')
                  AND c.service NOT IN ('load-generator', 'otelcol-contrib', 'flagd', 'flagd-ui', 'telemetry-docs', 'unknown_service')
                GROUP BY p.service, c.service
                ORDER BY calls DESC, source, target
                """, (rs, row) -> {
            long calls = rs.getLong("calls");
            long errors = rs.getLong("errors");
            return new TopologyEdge(
                    rs.getString("source"),
                    rs.getString("target"),
                    calls,
                    errors,
                    calls == 0 ? 0.0 : round2((errors * 100.0) / calls),
                    round2(rs.getDouble("avg_duration_ms")),
                    round2(rs.getDouble("p95_duration_ms")));
        }, org, start, end);

        record RawNode(String service, long spans, long errorSpans, double avgMs, double p95Ms) {
        }
        List<RawNode> rawNodes = jdbc.query("""
                SELECT service,
                       count(*) AS spans,
                       count(*) FILTER (WHERE status_code = 2) AS error_spans,
                       COALESCE(avg(duration_ns) / 1e6, 0) AS avg_duration_ms,
                       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ns) / 1e6, 0) AS p95_duration_ms
                FROM telemetry_spans
                WHERE organization_id = ?
                  AND start_time >= ? AND start_time <= ?
                  AND service NOT IN ('load-generator', 'otelcol-contrib', 'flagd', 'flagd-ui', 'telemetry-docs', 'unknown_service')
                  AND name NOT LIKE 'flagd.evaluation.%'
                GROUP BY service
                ORDER BY service
                """, (rs, row) -> new RawNode(
                rs.getString("service"),
                rs.getLong("spans"),
                rs.getLong("error_spans"),
                rs.getDouble("avg_duration_ms"),
                rs.getDouble("p95_duration_ms")), org, start, end);

        java.util.Set<String> errorServices = new java.util.HashSet<>();
        for (RawNode n : rawNodes) {
            if (n.errorSpans() > 0) {
                errorServices.add(n.service());
            }
        }

        List<TopologyNode> nodes = new ArrayList<>();
        boolean anyRootCause = false;
        for (RawNode n : rawNodes) {
            boolean hasFailingDownstream = edges.stream()
                    .anyMatch(e -> e.source().equals(n.service()) && (e.errors() > 0 || errorServices.contains(e.target())));
            boolean rootCause = n.errorSpans() > 0 && !hasFailingDownstream;
            if (rootCause) {
                anyRootCause = true;
            }
            nodes.add(new TopologyNode(
                    n.service(),
                    n.spans(),
                    n.errorSpans(),
                    n.spans() == 0 ? 0.0 : round2((n.errorSpans() * 100.0) / n.spans()),
                    round2(n.avgMs()),
                    round2(n.p95Ms()),
                    rootCause));
        }

        // Fallback if a cycle prevented any failing service from being marked as root-cause
        if (!errorServices.isEmpty() && !anyRootCause) {
            int worstIdx = -1;
            double worstRate = -1;
            for (int i = 0; i < nodes.size(); i++) {
                TopologyNode n = nodes.get(i);
                if (n.errorSpans() > 0 && n.errorRatePercent() > worstRate) {
                    worstRate = n.errorRatePercent();
                    worstIdx = i;
                }
            }
            if (worstIdx >= 0) {
                TopologyNode w = nodes.get(worstIdx);
                nodes.set(worstIdx, new TopologyNode(w.service(), w.spans(), w.errorSpans(),
                        w.errorRatePercent(), w.avgDurationMs(), w.p95DurationMs(), true));
            }
        }

        return new ServiceTopology(window.from(), window.to(), nodes, edges);
    }

    /**
     * RED time series (Rate in req/min, Error rate in %, Duration p50/p95/p99 in ms) for a service or all services.
     */
    public RedSeries red(String service, Instant from, Instant to) {
        UUID org = currentUser.get().organizationId();
        Window window = window(from, to);
        long windowSeconds = Math.max(60L, Duration.between(window.from(), window.to()).getSeconds());
        long stepSeconds = windowSeconds <= 1800 ? 60
                : windowSeconds <= 7200 ? 120
                : windowSeconds <= 21600 ? 300 : 900;

        String cleanService = service != null && !service.isBlank() ? service.trim() : null;
        List<Object> bucketParams = new ArrayList<>(List.of(stepSeconds, stepSeconds, org, ts(window.from()), ts(window.to())));
        List<Object> summaryParams = new ArrayList<>(List.of(org, ts(window.from()), ts(window.to())));
        String serviceClause = "";
        if (cleanService != null) {
            serviceClause = " AND service = ?";
            bucketParams.add(cleanService);
            summaryParams.add(cleanService);
        }

        List<RedBucket> buckets = jdbc.query("""
                SELECT to_timestamp(floor(extract(epoch from start_time) / ?) * ?) AS bucket,
                       count(*) AS requests,
                       count(*) FILTER (WHERE status_code = 2) AS errors,
                       COALESCE(percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_ns) / 1e6, 0) AS p50_ms,
                       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ns) / 1e6, 0) AS p95_ms,
                       COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ns) / 1e6, 0) AS p99_ms
                FROM telemetry_spans
                WHERE organization_id = ?
                  AND start_time >= ? AND start_time <= ?
                  AND name NOT LIKE 'flagd.evaluation.%'""" + serviceClause + """

                GROUP BY bucket
                ORDER BY bucket ASC
                """, (rs, row) -> {
            long requests = rs.getLong("requests");
            long errors = rs.getLong("errors");
            return new RedBucket(
                    rs.getTimestamp("bucket").toInstant(),
                    requests,
                    errors,
                    round2(requests * (60.0 / stepSeconds)),
                    requests == 0 ? 0.0 : round2((errors * 100.0) / requests),
                    round2(rs.getDouble("p50_ms")),
                    round2(rs.getDouble("p95_ms")),
                    round2(rs.getDouble("p99_ms")));
        }, bucketParams.toArray());

        record SummaryRow(long requests, long errors, double p50, double p95, double p99) {
        }
        SummaryRow summary = jdbc.queryForObject("""
                SELECT count(*) AS requests,
                       count(*) FILTER (WHERE status_code = 2) AS errors,
                       COALESCE(percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_ns) / 1e6, 0) AS p50_ms,
                       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ns) / 1e6, 0) AS p95_ms,
                       COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ns) / 1e6, 0) AS p99_ms
                FROM telemetry_spans
                WHERE organization_id = ?
                  AND start_time >= ? AND start_time <= ?
                  AND name NOT LIKE 'flagd.evaluation.%'""" + serviceClause,
                (rs, row) -> new SummaryRow(
                        rs.getLong("requests"),
                        rs.getLong("errors"),
                        rs.getDouble("p50_ms"),
                        rs.getDouble("p95_ms"),
                        rs.getDouble("p99_ms")),
                summaryParams.toArray());

        long totalReq = summary == null ? 0 : summary.requests();
        long totalErr = summary == null ? 0 : summary.errors();
        double windowMinutes = Math.max(1.0, windowSeconds / 60.0);

        return new RedSeries(
                window.from(),
                window.to(),
                cleanService,
                stepSeconds,
                totalReq,
                totalErr,
                round2(totalReq / windowMinutes),
                totalReq == 0 ? 0.0 : round2((totalErr * 100.0) / totalReq),
                round2(summary == null ? 0.0 : summary.p50()),
                round2(summary == null ? 0.0 : summary.p95()),
                round2(summary == null ? 0.0 : summary.p99()),
                buckets);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Window window(Instant from, Instant to) {
        Instant end = to != null ? to : clock.instant();
        Instant start = from != null ? from : end.minus(DEFAULT_WINDOW);
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("from deve ser anterior a to");
        }
        return new Window(start, end);
    }

    private static int clamp(Integer limit, int max) {
        return limit == null || limit < 1 ? Math.min(DEFAULT_LIMIT, max) : Math.min(limit, max);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static double millis(Instant start, Instant end) {
        return Duration.between(start, end).toNanos() / 1e6;
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }

    private static Map<String, Object> attributes(String json) {
        return json == null || json.isBlank() ? Map.of() : JSON.readValue(json, MAP_TYPE);
    }

    private record Window(Instant from, Instant to) {
    }
}
