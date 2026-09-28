package br.com.argos.argos_api.incident.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IncidentEventRepository extends JpaRepository<IncidentEvent, UUID> {

    List<IncidentEvent> findByIncidentIdAndOrganizationIdOrderByOccurredAtAscSeqAsc(UUID incidentId, UUID organizationId);
}
