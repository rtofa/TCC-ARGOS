package br.com.argos.argos_api.incident.dto;

import br.com.argos.argos_api.incident.domain.MetricsRow;
import br.com.argos.argos_api.incident.domain.Severity;
import br.com.argos.argos_api.incident.domain.SeverityMetricsRow;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public record IncidentMetricsResponse(
        Instant openedFrom,
        Instant openedTo,
        MetricsBucket overall,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<SeverityMetricsBucket> bySeverity) {

    public record MetricsBucket(long count, Long mttaSeconds, Long mttrSeconds) {

        public static MetricsBucket from(MetricsRow row) {
            return new MetricsBucket(row.getCount(), row.getMtta(), row.getMttr());
        }
    }

    public record SeverityMetricsBucket(Severity severity, long count, Long mttaSeconds, Long mttrSeconds) {

        public static SeverityMetricsBucket from(SeverityMetricsRow row) {
            return new SeverityMetricsBucket(Severity.valueOf(row.getSeverity()), row.getCount(),
                    row.getMtta(), row.getMttr());
        }
    }
}
