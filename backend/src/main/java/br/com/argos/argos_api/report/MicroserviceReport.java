package br.com.argos.argos_api.report;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Consolidated report built from OpenTelemetry signals and correlated operational events. */
public record MicroserviceReport(
        Instant generatedAt,
        Instant from,
        Instant to,
        String serviceFilter,
        Summary summary,
        List<ServiceRow> services,
        List<DependencyRow> dependencies,
        List<RedBucketRow> redSeries,
        List<MetricRow> metrics,
        List<ErrorTraceRow> errorTraces,
        List<LogRow> errorLogs,
        List<IncidentRow> incidents,
        List<DeployRow> deploys) {

    public record Summary(
            int totalServices,
            long totalRequests,
            long totalErrors,
            double requestsPerMinute,
            double errorRatePercent,
            double p50DurationMs,
            double p95DurationMs,
            double p99DurationMs,
            long totalLogs,
            long totalErrorLogs,
            int metricSeriesCount,
            int incidentsCount,
            int deploysCount) {
    }

    public record ServiceRow(
            String service,
            long spans,
            long errorSpans,
            double errorRatePercent,
            double avgDurationMs,
            double p95DurationMs,
            boolean rootCause,
            long logs,
            long errorLogs,
            Instant lastSeen) {
    }

    public record DependencyRow(
            String source,
            String target,
            long calls,
            long errors,
            double errorRatePercent,
            double avgDurationMs,
            double p95DurationMs) {
    }

    public record RedBucketRow(
            Instant bucket,
            long requests,
            long errors,
            double requestsPerMinute,
            double errorRatePercent,
            double p50DurationMs,
            double p95DurationMs,
            double p99DurationMs) {
    }

    public record MetricRow(
            String name,
            String service,
            String unit,
            long points,
            Instant lastSeen) {
    }

    public record ErrorTraceRow(
            String traceId,
            Instant startTime,
            double durationMs,
            String rootService,
            String rootName,
            long spanCount,
            long errorCount,
            List<String> services) {
    }

    public record LogRow(
            Instant occurredAt,
            String service,
            String severity,
            String body,
            String traceId) {
    }

    public record IncidentRow(
            UUID id,
            String title,
            String severity,
            String status,
            String source,
            List<String> affectedServices,
            Instant openedAt,
            Instant resolvedAt) {
    }

    public record DeployRow(
            String service,
            String version,
            String environment,
            String author,
            Instant deployedAt) {
    }
}
