package br.com.argos.argos_api.delivery;

import br.com.argos.argos_api.delivery.dto.DoraMetricsResponse;
import br.com.argos.argos_api.delivery.dto.DoraMetricsResponse.ChangeFailureRateMetric;
import br.com.argos.argos_api.delivery.dto.DoraMetricsResponse.CorrelatedDeployIncident;
import br.com.argos.argos_api.delivery.dto.DoraMetricsResponse.DeploymentFrequencyMetric;
import br.com.argos.argos_api.delivery.dto.DoraMetricsResponse.LeadTimeMetric;
import br.com.argos.argos_api.delivery.dto.DoraMetricsResponse.MeanTimeToRestoreMetric;
import br.com.argos.argos_api.shared.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Calculates the 4 official DORA metrics (Deployment Frequency, Lead Time for Changes,
 * Change Failure Rate, Mean Time to Restore) and correlates deploys with subsequent incidents.
 */
@Service
@Transactional(readOnly = true)
public class DoraMetricsService {

    static final Duration DEFAULT_PERIOD = Duration.ofDays(30);
    static final int DEFAULT_FAILURE_WINDOW_MINUTES = 60;

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final Clock clock;

    public DoraMetricsService(JdbcTemplate jdbc, CurrentUser currentUser, Clock clock) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public DoraMetricsResponse calculate(Instant from, Instant to, String service, Integer failureWindowMinutes) {
        Instant end = (to != null ? to : clock.instant()).truncatedTo(ChronoUnit.MICROS);
        Instant start = (from != null ? from : end.minus(DEFAULT_PERIOD)).truncatedTo(ChronoUnit.MICROS);
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("to deve ser posterior a from");
        }
        int windowMins = failureWindowMinutes == null
                ? DEFAULT_FAILURE_WINDOW_MINUTES
                : Math.min(Math.max(failureWindowMinutes, 1), 1440);
        String cleanService = service != null && !service.isBlank() ? service.trim() : null;
        UUID orgId = currentUser.get().organizationId();

        // 1. Deployment Frequency & Lead Time for Changes
        List<Object> deployParams = new ArrayList<>(List.of(orgId, Timestamp.from(start), Timestamp.from(end)));
        String deployServiceFilter = "";
        if (cleanService != null) {
            deployServiceFilter = " AND service = ?";
            deployParams.add(cleanService);
        }

        record DeployStats(long totalDeploys, long leadSamples, Long avgLeadSec, Long medianLeadSec) {
        }
        DeployStats deployStats = jdbc.queryForObject("""
                WITH lead_times AS (
                    SELECT CASE
                               WHEN committed_at IS NOT NULL AND deployed_at >= committed_at
                                   THEN extract(epoch FROM (deployed_at - committed_at))
                               WHEN received_at > deployed_at
                                   THEN extract(epoch FROM (received_at - deployed_at))
                           END AS lead_sec
                    FROM deploys
                    WHERE organization_id = ?
                      AND deployed_at >= ? AND deployed_at < ?""" + deployServiceFilter + """
                )
                SELECT count(*) AS total_deploys,
                       count(lead_sec) AS lead_samples,
                       CAST(round(avg(lead_sec)) AS bigint) AS avg_lead_sec,
                       CAST(round(percentile_cont(0.50) WITHIN GROUP (ORDER BY lead_sec)) AS bigint) AS median_lead_sec
                FROM lead_times
                """, (rs, row) -> new DeployStats(
                rs.getLong("total_deploys"),
                rs.getLong("lead_samples"),
                (Long) rs.getObject("avg_lead_sec"),
                (Long) rs.getObject("median_lead_sec")), deployParams.toArray());

        long totalDeploys = deployStats == null ? 0 : deployStats.totalDeploys();
        double days = Math.max(1.0, Duration.between(start, end).toSeconds() / 86400.0);
        double deploysPerDay = round2(totalDeploys / days);
        double deploysPerWeek = round2(deploysPerDay * 7.0);

        DeploymentFrequencyMetric deploymentFrequency = new DeploymentFrequencyMetric(
                totalDeploys,
                deploysPerDay,
                deploysPerWeek,
                classifyDeploymentFrequency(totalDeploys, deploysPerDay, deploysPerWeek));

        Long avgLead = deployStats == null ? null : deployStats.avgLeadSec();
        Long medianLead = deployStats == null ? null : deployStats.medianLeadSec();
        long leadSamples = deployStats == null ? 0 : deployStats.leadSamples();
        LeadTimeMetric leadTime = new LeadTimeMetric(
                avgLead,
                medianLead,
                leadSamples,
                classifyLeadTime(avgLead));

        // 2. Change Failure Rate & correlated deploy->incident pairs
        List<Object> corrParams = new ArrayList<>(List.of(windowMins, orgId, Timestamp.from(start), Timestamp.from(end)));
        String corrServiceFilter = "";
        if (cleanService != null) {
            corrServiceFilter = " AND d.service = ?";
            corrParams.add(cleanService);
        }

        List<CorrelatedDeployIncident> correlated = jdbc.query("""
                SELECT d.id AS deploy_id,
                       d.service,
                       d.version,
                       d.author,
                       d.deployed_at,
                       i.id AS incident_id,
                       i.title AS incident_title,
                       i.severity,
                       i.opened_at AS incident_opened_at,
                       CAST(round(extract(epoch FROM (i.opened_at - d.deployed_at)) / 60.0) AS bigint) AS mins_after
                FROM deploys d
                JOIN LATERAL (
                    SELECT inc.id, inc.title, inc.severity, inc.opened_at
                    FROM incidents inc
                    WHERE inc.organization_id = d.organization_id
                      AND d.service = ANY(inc.affected_services)
                      AND inc.opened_at >= d.deployed_at
                      AND inc.opened_at <= d.deployed_at + make_interval(mins => ?)
                    ORDER BY inc.opened_at ASC
                    LIMIT 1
                ) i ON TRUE
                WHERE d.organization_id = ?
                  AND d.deployed_at >= ? AND d.deployed_at < ?""" + corrServiceFilter + """

                ORDER BY d.deployed_at DESC
                """, (rs, row) -> new CorrelatedDeployIncident(
                rs.getObject("deploy_id", UUID.class),
                rs.getString("service"),
                rs.getString("version"),
                rs.getString("author"),
                rs.getTimestamp("deployed_at").toInstant(),
                rs.getObject("incident_id", UUID.class),
                rs.getString("incident_title"),
                rs.getString("severity"),
                rs.getTimestamp("incident_opened_at").toInstant(),
                rs.getLong("mins_after")), corrParams.toArray());

        long failedDeploys = correlated.size();
        double cfrPercent = totalDeploys == 0 ? 0.0 : round2((failedDeploys * 100.0) / totalDeploys);
        ChangeFailureRateMetric changeFailureRate = new ChangeFailureRateMetric(
                totalDeploys,
                failedDeploys,
                cfrPercent,
                classifyChangeFailureRate(totalDeploys, cfrPercent));

        // 3. Mean Time to Restore (MTTR)
        List<Object> incParams = new ArrayList<>(List.of(orgId, Timestamp.from(start), Timestamp.from(end)));
        String incServiceFilter = "";
        if (cleanService != null) {
            incServiceFilter = " AND ? = ANY(affected_services)";
            incParams.add(cleanService);
        }

        record IncidentStats(long total, long resolved, Long mttrSec, Long mttaSec) {
        }
        IncidentStats incStats = jdbc.queryForObject("""
                SELECT count(*) AS total_incidents,
                       count(resolved_at) AS resolved_incidents,
                       CAST(round(avg(extract(epoch FROM (resolved_at - opened_at)))) AS bigint) AS mttr_sec,
                       CAST(round(avg(extract(epoch FROM (acknowledged_at - opened_at)))) AS bigint) AS mtta_sec
                FROM incidents
                WHERE organization_id = ?
                  AND opened_at >= ? AND opened_at < ?""" + incServiceFilter,
                (rs, row) -> new IncidentStats(
                        rs.getLong("total_incidents"),
                        rs.getLong("resolved_incidents"),
                        (Long) rs.getObject("mttr_sec"),
                        (Long) rs.getObject("mtta_sec")),
                incParams.toArray());

        Long mttrSec = incStats == null ? null : incStats.mttrSec();
        Long mttaSec = incStats == null ? null : incStats.mttaSec();
        MeanTimeToRestoreMetric mttr = new MeanTimeToRestoreMetric(
                incStats == null ? 0 : incStats.total(),
                incStats == null ? 0 : incStats.resolved(),
                mttrSec,
                mttaSec,
                classifyMttr(mttrSec));

        return new DoraMetricsResponse(
                start,
                end,
                cleanService,
                windowMins,
                deploymentFrequency,
                leadTime,
                changeFailureRate,
                mttr,
                correlated);
    }

    private static String classifyDeploymentFrequency(long totalDeploys, double perDay, double perWeek) {
        if (totalDeploys == 0) return "LOW";
        if (perDay >= 1.0) return "ELITE";
        if (perWeek >= 1.0) return "HIGH";
        if (perWeek >= 0.25) return "MEDIUM";
        return "LOW";
    }

    private static String classifyLeadTime(Long avgSeconds) {
        if (avgSeconds == null) return "N/A";
        if (avgSeconds < 3600) return "ELITE";
        if (avgSeconds < 86400 * 7) return "HIGH";
        if (avgSeconds < 86400 * 30) return "MEDIUM";
        return "LOW";
    }

    private static String classifyChangeFailureRate(long totalDeploys, double pct) {
        if (totalDeploys == 0) return "N/A";
        if (pct <= 5.0) return "ELITE";
        if (pct <= 15.0) return "HIGH";
        if (pct <= 30.0) return "MEDIUM";
        return "LOW";
    }

    private static String classifyMttr(Long mttrSeconds) {
        if (mttrSeconds == null) return "N/A";
        if (mttrSeconds < 3600) return "ELITE";
        if (mttrSeconds < 86400) return "HIGH";
        if (mttrSeconds < 86400 * 7) return "MEDIUM";
        return "LOW";
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
