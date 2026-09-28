package br.com.argos.argos_api.incident;

import br.com.argos.argos_api.incident.domain.Incident;
import br.com.argos.argos_api.incident.domain.IncidentEvent;
import br.com.argos.argos_api.incident.domain.IncidentEventRepository;
import br.com.argos.argos_api.incident.domain.IncidentRepository;
import br.com.argos.argos_api.incident.domain.IncidentStatus;
import br.com.argos.argos_api.incident.domain.Severity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Public API for modules that manage incidents on behalf of the system (e.g. alerting).
 * The organization is always explicit because there is no authenticated user; severity is
 * exchanged as text ("SEV1".."SEV4") so callers do not depend on this module's domain.
 */
@Service
public class AutomatedIncidents {

    private static final int MAX_TITLE_LENGTH = 200;

    private final IncidentRepository incidentRepository;
    private final IncidentEventRepository eventRepository;
    private final Clock clock;

    public AutomatedIncidents(IncidentRepository incidentRepository, IncidentEventRepository eventRepository,
                              Clock clock) {
        this.incidentRepository = incidentRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    @Transactional
    public UUID openFromAlert(UUID organizationId, UUID apiKeyId, String title, String description, String severity,
                              List<String> services, String dedupKey, String sourceName) {
        Instant now = now();
        String safeTitle = title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
        Incident incident = incidentRepository.save(Incident.openFromAlert(organizationId, safeTitle, description,
                Severity.valueOf(severity), services, now));
        eventRepository.save(IncidentEvent.alertTriggered(incident, apiKeyId, now, dedupKey, sourceName));
        return incident.getId();
    }

    /** An incident that does not exist in the organization is treated as resolved. */
    @Transactional(readOnly = true)
    public boolean isResolved(UUID organizationId, UUID incidentId) {
        return incidentRepository.findByIdAndOrganizationId(incidentId, organizationId)
                .map(incident -> incident.getStatus() == IncidentStatus.RESOLVED)
                .orElse(true);
    }

    @Transactional
    public void updateSeverityFromAlert(UUID organizationId, UUID incidentId, String severity, UUID apiKeyId) {
        Severity newSeverity = Severity.valueOf(severity);
        incidentRepository.findByIdAndOrganizationId(incidentId, organizationId).ifPresent(incident -> {
            List<IncidentEvent> events = incident.changeSeverityByAlert(newSeverity, apiKeyId, now());
            if (!events.isEmpty()) {
                eventRepository.saveAll(events);
            }
        });
    }

    /** @return true if the incident was resolved now; false if it was already resolved or does not exist. */
    @Transactional
    public boolean resolveFromAlert(UUID organizationId, UUID incidentId, UUID apiKeyId) {
        return incidentRepository.findByIdAndOrganizationId(incidentId, organizationId)
                .map(incident -> {
                    List<IncidentEvent> events = incident.resolveByAlert(apiKeyId, now());
                    if (events.isEmpty()) {
                        return false;
                    }
                    eventRepository.saveAll(events);
                    return true;
                })
                .orElse(false);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
