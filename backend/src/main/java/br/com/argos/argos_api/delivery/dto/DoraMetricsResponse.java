package br.com.argos.argos_api.delivery.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DoraMetricsResponse(
        Instant from,
        Instant to,
        String service,
        int failureWindowMinutes,
        DeploymentFrequencyMetric deploymentFrequency,
        LeadTimeMetric leadTimeForChanges,
        ChangeFailureRateMetric changeFailureRate,
        MeanTimeToRestoreMetric meanTimeToRestore,
        List<CorrelatedDeployIncident> correlatedIncidents) {

    public record DeploymentFrequencyMetric(
            long totalDeploys,
            double deploysPerDay,
            double deploysPerWeek,
            String level) {
    }

    public record LeadTimeMetric(
            Long averageSeconds,
            Long medianSeconds,
            long sampleSize,
            String level) {
    }

    public record ChangeFailureRateMetric(
            long totalDeploys,
            long failedDeploys,
            double percentage,
            String level) {
    }

    public record MeanTimeToRestoreMetric(
            long totalIncidents,
            long resolvedIncidents,
            Long mttrSeconds,
            Long mttaSeconds,
            String level) {
    }

    public record CorrelatedDeployIncident(
            UUID deployId,
            String service,
            String version,
            String author,
            Instant deployedAt,
            UUID incidentId,
            String incidentTitle,
            String severity,
            Instant incidentOpenedAt,
            long minutesAfterDeploy) {
    }
}
