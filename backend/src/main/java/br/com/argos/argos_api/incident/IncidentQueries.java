package br.com.argos.argos_api.incident;

import br.com.argos.argos_api.incident.domain.Incident;
import br.com.argos.argos_api.incident.domain.IncidentEvent;
import br.com.argos.argos_api.incident.domain.IncidentEventRepository;
import br.com.argos.argos_api.incident.domain.IncidentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only view of incidents for other modules (AI investigations, business impact). The organization is always
 * explicit, so callers running outside a request (queues, schedulers) do not depend on an authenticated user.
 */
@Service
public class IncidentQueries {

    private final IncidentRepository incidentRepository;
    private final IncidentEventRepository eventRepository;

    public IncidentQueries(IncidentRepository incidentRepository, IncidentEventRepository eventRepository) {
        this.incidentRepository = incidentRepository;
        this.eventRepository = eventRepository;
    }

    /** Severity, status and source travel as text ("SEV1", "OPEN", "ALERT") so callers need no domain types. */
    public record IncidentSnapshot(UUID id, UUID organizationId, String title, String description, String severity,
                                   String status, String source, List<String> affectedServices, Instant openedAt,
                                   Instant acknowledgedAt, Instant resolvedAt) {

        public boolean resolved() {
            return resolvedAt != null;
        }
    }

    /** One timeline entry; {@code bySystem} marks entries without a human actor (alerts, automations). */
    public record TimelineEntry(Instant occurredAt, String type, String oldValue, String newValue, String body,
                                boolean bySystem) {
    }

    @Transactional(readOnly = true)
    public Optional<IncidentSnapshot> find(UUID organizationId, UUID incidentId) {
        return incidentRepository.findByIdAndOrganizationId(incidentId, organizationId).map(IncidentQueries::snapshot);
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID organizationId, UUID incidentId) {
        return eventRepository.findByIncidentIdAndOrganizationIdOrderByOccurredAtAscSeqAsc(incidentId, organizationId)
                .stream()
                .map(IncidentQueries::entry)
                .toList();
    }

    /** Incidents whose lifetime overlaps the window (still open, or resolved after {@code from}). */
    @Transactional(readOnly = true)
    public List<IncidentSnapshot> overlapping(UUID organizationId, Instant from, Instant to) {
        return incidentRepository.findOverlapping(organizationId, from, to).stream()
                .map(IncidentQueries::snapshot)
                .toList();
    }

    private static IncidentSnapshot snapshot(Incident incident) {
        return new IncidentSnapshot(incident.getId(), incident.getOrganizationId(), incident.getTitle(),
                incident.getDescription(), incident.getSeverity().name(), incident.getStatus().name(),
                incident.getSource().name(), List.copyOf(incident.getAffectedServices()), incident.getOpenedAt(),
                incident.getAcknowledgedAt(), incident.getResolvedAt());
    }

    private static TimelineEntry entry(IncidentEvent event) {
        return new TimelineEntry(event.getOccurredAt(), event.getType().name(), event.getOldValue(),
                event.getNewValue(), event.getBody(), event.getActorId() == null);
    }
}
