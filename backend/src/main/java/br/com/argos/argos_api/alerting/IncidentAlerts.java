package br.com.argos.argos_api.alerting;

import br.com.argos.argos_api.alerting.domain.AlertIncidentLink;
import br.com.argos.argos_api.alerting.domain.AlertIncidentLinkRepository;
import br.com.argos.argos_api.alerting.domain.AlertSource;
import br.com.argos.argos_api.alerting.domain.AlertSourceRepository;
import br.com.argos.argos_api.alerting.mapping.ArgosDefaultMapping;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Alerts linked to an incident, for other modules (the AI investigation explains each of them, AI-05). */
@Service
public class IncidentAlerts {

    private final AlertIncidentLinkRepository linkRepository;
    private final AlertSourceRepository sourceRepository;

    public IncidentAlerts(AlertIncidentLinkRepository linkRepository, AlertSourceRepository sourceRepository) {
        this.linkRepository = linkRepository;
        this.sourceRepository = sourceRepository;
    }

    public record LinkedAlert(String sourceName, String dedupKey, int occurrences, Instant firstSeenAt,
                              Instant lastSeenAt, Instant closedAt) {
    }

    @Transactional(readOnly = true)
    public List<LinkedAlert> forIncident(UUID organizationId, UUID incidentId) {
        List<AlertIncidentLink> links =
                linkRepository.findByIncidentIdAndOrganizationIdOrderByFirstSeenAt(incidentId, organizationId);
        List<UUID> sourceIds = links.stream().map(AlertIncidentLink::getSourceId).filter(Objects::nonNull)
                .distinct().toList();
        Map<UUID, String> names = sourceIds.isEmpty() ? Map.of()
                : sourceRepository.findByOrganizationIdAndIdIn(organizationId, sourceIds).stream()
                        .collect(Collectors.toMap(AlertSource::getId, AlertSource::getName));
        return links.stream()
                .map(link -> new LinkedAlert(
                        link.getSourceId() == null ? ArgosDefaultMapping.SOURCE_NAME : names.get(link.getSourceId()),
                        link.getDedupKey(), link.getOccurrences(), link.getFirstSeenAt(), link.getLastSeenAt(),
                        link.getClosedAt()))
                .toList();
    }
}
