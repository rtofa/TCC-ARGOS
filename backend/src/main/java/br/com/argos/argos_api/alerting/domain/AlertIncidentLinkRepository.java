package br.com.argos.argos_api.alerting.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertIncidentLinkRepository extends JpaRepository<AlertIncidentLink, UUID> {

    Optional<AlertIncidentLink> findByOrganizationIdAndSourceIdIsNullAndDedupKeyAndClosedAtIsNull(
            UUID organizationId, String dedupKey);

    Optional<AlertIncidentLink> findByOrganizationIdAndSourceIdAndDedupKeyAndClosedAtIsNull(
            UUID organizationId, UUID sourceId, String dedupKey);

   
    default Optional<AlertIncidentLink> findOpen(UUID organizationId, UUID sourceId, String dedupKey) {
        return sourceId == null
                ? findByOrganizationIdAndSourceIdIsNullAndDedupKeyAndClosedAtIsNull(organizationId, dedupKey)
                : findByOrganizationIdAndSourceIdAndDedupKeyAndClosedAtIsNull(organizationId, sourceId, dedupKey);
    }

    List<AlertIncidentLink> findByIncidentIdAndOrganizationIdOrderByFirstSeenAt(UUID incidentId, UUID organizationId);

   
    @Query(value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))) AS l",
            nativeQuery = true)
    Long lockDedupKey(@Param("lockKey") String lockKey);
}
