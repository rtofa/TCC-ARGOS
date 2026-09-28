package br.com.argos.argos_api.alerting;

import br.com.argos.argos_api.alerting.domain.AlertIncidentLink;
import br.com.argos.argos_api.alerting.domain.AlertIncidentLinkRepository;
import br.com.argos.argos_api.alerting.dto.AlertAction;
import br.com.argos.argos_api.alerting.dto.AlertResult;
import br.com.argos.argos_api.alerting.dto.WebhookResult;
import br.com.argos.argos_api.alerting.mapping.AlertStatus;
import br.com.argos.argos_api.alerting.mapping.MappedAlert;
import br.com.argos.argos_api.apikey.ApiKeyPrincipal;
import br.com.argos.argos_api.incident.AutomatedIncidents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


@Service
public class AlertProcessor {

    private static final Logger log = LoggerFactory.getLogger(AlertProcessor.class);

    private final AutomatedIncidents automatedIncidents;
    private final AlertIncidentLinkRepository linkRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AlertProcessor(AutomatedIncidents automatedIncidents, AlertIncidentLinkRepository linkRepository,
                          TransactionTemplate transactionTemplate, Clock clock) {
        this.automatedIncidents = automatedIncidents;
        this.linkRepository = linkRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public WebhookResult process(ApiKeyPrincipal key, UUID sourceId, String sourceName, List<MappedAlert> alerts) {
        List<AlertResult> results = new ArrayList<>();
        for (MappedAlert alert : alerts) {
            results.add(processOne(key, sourceId, sourceName, alert));
        }
        return WebhookResult.of(results);
    }

    private AlertResult processOne(ApiKeyPrincipal key, UUID sourceId, String sourceName, MappedAlert alert) {
        if (alert.rejected()) {
            return AlertResult.rejected(alert.index(), alert.dedupKey(), alert.rejectionReason());
        }
        try {
            return transactionTemplate.execute(status -> apply(key, sourceId, sourceName, alert));
        } catch (RuntimeException e) {
            log.warn("Failed to process alert {} of organization {}", alert.dedupKey(), key.organizationId(), e);
            return AlertResult.rejected(alert.index(), alert.dedupKey(), "erro ao processar alerta");
        }
    }

    private AlertResult apply(ApiKeyPrincipal key, UUID sourceId, String sourceName, MappedAlert alert) {
        UUID organizationId = key.organizationId();
        linkRepository.lockDedupKey(organizationId + ":" + (sourceId == null ? "default" : sourceId)
                + ":" + alert.dedupKey());
        Optional<AlertIncidentLink> link = linkRepository.findOpen(organizationId, sourceId, alert.dedupKey());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        if (alert.status() == AlertStatus.RESOLVED) {
            if (link.isEmpty()) {
                return AlertResult.of(alert.index(), alert.dedupKey(), AlertAction.IGNORED, null);
            }
            UUID incidentId = link.get().getIncidentId();
            boolean resolved = automatedIncidents.resolveFromAlert(organizationId, incidentId, key.keyId());
            link.get().close(now);
            return AlertResult.of(alert.index(), alert.dedupKey(),
                    resolved ? AlertAction.RESOLVED : AlertAction.IGNORED, incidentId);
        }

        if (link.isPresent()) {
            UUID incidentId = link.get().getIncidentId();
            if (!automatedIncidents.isResolved(organizationId, incidentId)) {
                link.get().registerOccurrence(now);
                automatedIncidents.updateSeverityFromAlert(organizationId, incidentId, alert.severity(), key.keyId());
                return AlertResult.of(alert.index(), alert.dedupKey(), AlertAction.UPDATED, incidentId);
            }
            // Resolved by a person meanwhile: close the old link before opening a new one
            // (flush now, otherwise Hibernate inserts before updating and hits the unique index).
            link.get().close(now);
            linkRepository.saveAndFlush(link.get());
        }

        UUID incidentId = automatedIncidents.openFromAlert(organizationId, key.keyId(), alert.title(),
                alert.description(), alert.severity(), alert.services(), alert.dedupKey(), sourceName);
        linkRepository.save(AlertIncidentLink.open(organizationId, sourceId, alert.dedupKey(), incidentId, now));
        return AlertResult.of(alert.index(), alert.dedupKey(), AlertAction.OPENED, incidentId);
    }
}
