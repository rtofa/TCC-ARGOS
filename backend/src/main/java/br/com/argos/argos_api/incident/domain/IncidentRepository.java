package br.com.argos.argos_api.incident.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

    Optional<Incident> findByIdAndOrganizationId(UUID id, UUID organizationId);

    // avg() ignores NULLs, so incidents still pending acknowledgement/resolution stay out of each average.
    @Query(value = """
            SELECT count(*) AS count,
                   CAST(round(avg(extract(epoch FROM (acknowledged_at - opened_at)))) AS bigint) AS mtta,
                   CAST(round(avg(extract(epoch FROM (resolved_at - opened_at)))) AS bigint) AS mttr
            FROM incidents
            WHERE organization_id = :organizationId AND opened_at >= :openedFrom AND opened_at < :openedTo
            """, nativeQuery = true)
    MetricsRow aggregateMetrics(@Param("organizationId") UUID organizationId,
                                @Param("openedFrom") Instant openedFrom,
                                @Param("openedTo") Instant openedTo);

    @Query(value = """
            SELECT severity AS severity,
                   count(*) AS count,
                   CAST(round(avg(extract(epoch FROM (acknowledged_at - opened_at)))) AS bigint) AS mtta,
                   CAST(round(avg(extract(epoch FROM (resolved_at - opened_at)))) AS bigint) AS mttr
            FROM incidents
            WHERE organization_id = :organizationId AND opened_at >= :openedFrom AND opened_at < :openedTo
            GROUP BY severity
            ORDER BY severity
            """, nativeQuery = true)
    List<SeverityMetricsRow> aggregateMetricsBySeverity(@Param("organizationId") UUID organizationId,
                                                        @Param("openedFrom") Instant openedFrom,
                                                        @Param("openedTo") Instant openedTo);
}
