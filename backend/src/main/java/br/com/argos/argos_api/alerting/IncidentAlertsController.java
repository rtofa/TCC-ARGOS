package br.com.argos.argos_api.alerting;

import br.com.argos.argos_api.alerting.domain.AlertIncidentLink;
import br.com.argos.argos_api.alerting.domain.AlertIncidentLinkRepository;
import br.com.argos.argos_api.alerting.domain.AlertSource;
import br.com.argos.argos_api.alerting.domain.AlertSourceRepository;
import br.com.argos.argos_api.alerting.dto.IncidentAlertLinkResponse;
import br.com.argos.argos_api.alerting.mapping.ArgosDefaultMapping;
import br.com.argos.argos_api.shared.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class IncidentAlertsController {

    private final AlertIncidentLinkRepository linkRepository;
    private final AlertSourceRepository sourceRepository;
    private final CurrentUser currentUser;

    public IncidentAlertsController(AlertIncidentLinkRepository linkRepository, AlertSourceRepository sourceRepository,
                                    CurrentUser currentUser) {
        this.linkRepository = linkRepository;
        this.sourceRepository = sourceRepository;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/incidents/{id}/alerts")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    @Transactional(readOnly = true)
    public List<IncidentAlertLinkResponse> alerts(@PathVariable UUID id) {
        UUID organizationId = currentUser.get().organizationId();
        List<AlertIncidentLink> links = linkRepository.findByIncidentIdAndOrganizationIdOrderByFirstSeenAt(id, organizationId);
        List<UUID> sourceIds = links.stream().map(AlertIncidentLink::getSourceId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> names = sourceIds.isEmpty() ? Map.of()
                : sourceRepository.findByOrganizationIdAndIdIn(organizationId, sourceIds).stream()
                        .collect(Collectors.toMap(AlertSource::getId, AlertSource::getName));
        return links.stream()
                .map(link -> new IncidentAlertLinkResponse(link.getSourceId(),
                        link.getSourceId() == null ? ArgosDefaultMapping.SOURCE_NAME : names.get(link.getSourceId()),
                        link.getDedupKey(), link.getOccurrences(), link.getFirstSeenAt(), link.getLastSeenAt(),
                        link.getClosedAt()))
                .toList();
    }
}
