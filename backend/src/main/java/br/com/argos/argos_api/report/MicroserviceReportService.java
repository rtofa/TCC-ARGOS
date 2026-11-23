package br.com.argos.argos_api.report;

import br.com.argos.argos_api.delivery.DeployHistory;
import br.com.argos.argos_api.incident.IncidentQueries;
import br.com.argos.argos_api.incident.IncidentQueries.IncidentSnapshot;
import br.com.argos.argos_api.report.MicroserviceReport.DependencyRow;
import br.com.argos.argos_api.report.MicroserviceReport.DeployRow;
import br.com.argos.argos_api.report.MicroserviceReport.ErrorTraceRow;
import br.com.argos.argos_api.report.MicroserviceReport.IncidentRow;
import br.com.argos.argos_api.report.MicroserviceReport.LogRow;
import br.com.argos.argos_api.report.MicroserviceReport.MetricRow;
import br.com.argos.argos_api.report.MicroserviceReport.RedBucketRow;
import br.com.argos.argos_api.report.MicroserviceReport.ServiceRow;
import br.com.argos.argos_api.report.MicroserviceReport.Summary;
import br.com.argos.argos_api.shared.security.CurrentUser;
import br.com.argos.argos_api.telemetry.TelemetryQueryService;
import br.com.argos.argos_api.telemetry.TelemetryViews.LogView;
import br.com.argos.argos_api.telemetry.TelemetryViews.MetricSeries;
import br.com.argos.argos_api.telemetry.TelemetryViews.RedSeries;
import br.com.argos.argos_api.telemetry.TelemetryViews.ServiceSummary;
import br.com.argos.argos_api.telemetry.TelemetryViews.ServiceTopology;
import br.com.argos.argos_api.telemetry.TelemetryViews.TopologyNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Aggregates OTLP telemetry (traces, logs, metrics, dependencies) and correlated incidents/deploys for export. */
@Service
public class MicroserviceReportService {

    static final Duration DEFAULT_WINDOW = Duration.ofHours(1);
    static final long MAX_DAYS = 93;
    private static final int TRACE_LIMIT = 50;
    private static final int LOG_LIMIT = 100;
    private static final int DEPLOY_LIMIT = 100;

    private final TelemetryQueryService telemetry;
    private final IncidentQueries incidents;
    private final DeployHistory deploys;
    private final CurrentUser currentUser;
    private final Clock clock;

    public MicroserviceReportService(TelemetryQueryService telemetry, IncidentQueries incidents,
                                     DeployHistory deploys, CurrentUser currentUser, Clock clock) {
        this.telemetry = telemetry;
        this.incidents = incidents;
        this.deploys = deploys;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MicroserviceReport generate(String service, Instant from, Instant to) {
        Instant now = clock.instant();
        Instant end = to != null ? to : now;
        Instant start = from != null ? from : end.minus(DEFAULT_WINDOW);
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("from deve ser anterior a to");
        }
        if (Duration.between(start, end).toDays() > MAX_DAYS) {
            throw new IllegalArgumentException("O período máximo do relatório é de " + MAX_DAYS + " dias");
        }

        UUID org = currentUser.get().organizationId();
        String serviceFilter = service != null && !service.isBlank() ? service.trim() : null;

        List<ServiceSummary> rawServices = telemetry.services(start, end);
        ServiceTopology topology = telemetry.topology(start, end);
        RedSeries red = telemetry.red(serviceFilter, start, end);
        List<MetricSeries> rawMetrics = telemetry.metricSeries(start, end);
        List<ErrorTraceRow> errorTraces = telemetry.traces(serviceFilter, true, null, start, end, TRACE_LIMIT).stream()
                .map(t -> new ErrorTraceRow(t.traceId(), t.startTime(), round2(t.durationMs()),
                        t.rootService(), t.rootName(), t.spanCount(), t.errorCount(), t.services()))
                .toList();
        List<LogRow> errorLogs = telemetry.logs(serviceFilter, "WARN", null, null, start, end, LOG_LIMIT).stream()
                .map(MicroserviceReportService::toLogRow)
                .toList();

        Map<String, TopologyNode> nodeByService = topology.nodes().stream()
                .collect(Collectors.toMap(TopologyNode::service, Function.identity(), (a, b) -> a));

        List<ServiceRow> serviceRows = rawServices.stream()
                .filter(s -> serviceFilter == null || s.service().equals(serviceFilter))
                .map(s -> toServiceRow(s, nodeByService.get(s.service())))
                .sorted(Comparator.comparingLong(ServiceRow::errorSpans).reversed()
                        .thenComparing(Comparator.comparingLong(ServiceRow::spans).reversed())
                        .thenComparing(ServiceRow::service))
                .toList();

        List<DependencyRow> dependencyRows = topology.edges().stream()
                .filter(e -> serviceFilter == null
                        || e.source().equals(serviceFilter)
                        || e.target().equals(serviceFilter))
                .map(e -> new DependencyRow(e.source(), e.target(), e.calls(), e.errors(),
                        e.errorRatePercent(), e.avgDurationMs(), e.p95DurationMs()))
                .toList();

        List<RedBucketRow> redRows = red.buckets().stream()
                .map(b -> new RedBucketRow(b.bucket(), b.requests(), b.errors(), b.requestsPerMinute(),
                        b.errorRatePercent(), b.p50DurationMs(), b.p95DurationMs(), b.p99DurationMs()))
                .toList();

        List<MetricRow> metricRows = rawMetrics.stream()
                .filter(m -> serviceFilter == null || m.service().equals(serviceFilter))
                .map(m -> new MetricRow(m.name(), m.service(), m.unit(), m.points(), m.lastSeen()))
                .toList();

        List<IncidentRow> incidentRows = incidents.overlapping(org, start, end).stream()
                .filter(i -> serviceFilter == null || i.affectedServices().contains(serviceFilter))
                .map(MicroserviceReportService::toIncidentRow)
                .toList();

        Set<String> deployFilter = serviceFilter == null ? Set.of() : Set.of(serviceFilter);
        List<DeployRow> deployRows = deploys.between(org, deployFilter, start, end, DEPLOY_LIMIT).stream()
                .map(d -> new DeployRow(d.service(), d.version(), d.environment(), d.author(), d.deployedAt()))
                .toList();

        long totalLogs = serviceRows.stream().mapToLong(ServiceRow::logs).sum();
        long totalErrorLogs = serviceRows.stream().mapToLong(ServiceRow::errorLogs).sum();

        Summary summary = new Summary(
                serviceRows.size(),
                red.totalRequests(),
                red.totalErrors(),
                red.requestsPerMinute(),
                red.errorRatePercent(),
                red.p50DurationMs(),
                red.p95DurationMs(),
                red.p99DurationMs(),
                totalLogs,
                totalErrorLogs,
                metricRows.size(),
                incidentRows.size(),
                deployRows.size());

        return new MicroserviceReport(
                now,
                start,
                end,
                serviceFilter,
                summary,
                serviceRows,
                dependencyRows,
                redRows,
                metricRows,
                errorTraces,
                errorLogs,
                incidentRows,
                deployRows);
    }

    private static ServiceRow toServiceRow(ServiceSummary summary, TopologyNode node) {
        double errorRate = summary.spans() == 0 ? 0.0 : round2((summary.errorSpans() * 100.0) / summary.spans());
        double avgMs = node != null ? node.avgDurationMs() : 0.0;
        double p95Ms = node != null ? node.p95DurationMs() : 0.0;
        boolean rootCause = node != null && node.rootCause();
        return new ServiceRow(
                summary.service(),
                summary.spans(),
                summary.errorSpans(),
                errorRate,
                avgMs,
                p95Ms,
                rootCause,
                summary.logs(),
                summary.errorLogs(),
                summary.lastSeen());
    }

    private static LogRow toLogRow(LogView log) {
        String level = log.severityText() != null && !log.severityText().isBlank()
                ? log.severityText()
                : log.severityNumber() >= 17 ? "ERROR" : "WARN";
        return new LogRow(log.occurredAt(), log.service(), level, log.body(), log.traceId());
    }

    private static IncidentRow toIncidentRow(IncidentSnapshot incident) {
        return new IncidentRow(
                incident.id(),
                incident.title(),
                incident.severity(),
                incident.status(),
                incident.source(),
                incident.affectedServices(),
                incident.openedAt(),
                incident.resolvedAt());
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
