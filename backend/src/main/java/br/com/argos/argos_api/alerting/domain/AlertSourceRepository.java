package br.com.argos.argos_api.alerting.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertSourceRepository extends JpaRepository<AlertSource, UUID> {

    Optional<AlertSource> findByIdAndOrganizationIdAndDeletedAtIsNull(UUID id, UUID organizationId);

    List<AlertSource> findByOrganizationIdAndDeletedAtIsNullOrderByName(UUID organizationId);

    /** Includes removed sources: incidents they created keep showing their name. */
    List<AlertSource> findByOrganizationIdAndIdIn(UUID organizationId, List<UUID> ids);
}
