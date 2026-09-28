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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertProcessorTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final ApiKeyPrincipal KEY = new ApiKeyPrincipal(UUID.randomUUID(), ORG, "argos_abcdef");
    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final UUID INCIDENT = UUID.randomUUID();
    private static final String SOURCE_NAME = "Formato padrão";

    @Mock
    private AutomatedIncidents automatedIncidents;
    @Mock
    private AlertIncidentLinkRepository linkRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private AlertProcessor processor;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        processor = new AlertProcessor(automatedIncidents, linkRepository, transactionTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MappedAlert firing(String severity) {
        return new MappedAlert(0, "CPU alta", "cpu", AlertStatus.FIRING, severity, List.of("checkout-api"), null, null);
    }

    private static MappedAlert resolved() {
        return new MappedAlert(0, "CPU alta", "cpu", AlertStatus.RESOLVED, "SEV1", List.of(), null, null);
    }

    private AlertResult processOne(MappedAlert alert) {
        WebhookResult result = processor.process(KEY, null, SOURCE_NAME, List.of(alert));
        return result.results().get(0);
    }

    private AlertIncidentLink openLink() {
        AlertIncidentLink link = AlertIncidentLink.open(ORG, null, "cpu", INCIDENT, NOW.minusSeconds(60));
        when(linkRepository.findOpen(ORG, null, "cpu")).thenReturn(Optional.of(link));
        return link;
    }

    @Test
    void firingWithoutOpenLinkOpensIncidentAndLink() {
        when(linkRepository.findOpen(ORG, null, "cpu")).thenReturn(Optional.empty());
        when(automatedIncidents.openFromAlert(ORG, KEY.keyId(), "CPU alta", null, "SEV1", List.of("checkout-api"),
                "cpu", SOURCE_NAME)).thenReturn(INCIDENT);

        AlertResult result = processOne(firing("SEV1"));

        assertThat(result.action()).isEqualTo(AlertAction.OPENED);
        assertThat(result.incidentId()).isEqualTo(INCIDENT);
        ArgumentCaptor<AlertIncidentLink> link = ArgumentCaptor.forClass(AlertIncidentLink.class);
        verify(linkRepository).save(link.capture());
        assertThat(link.getValue().getIncidentId()).isEqualTo(INCIDENT);
        assertThat(link.getValue().getOrganizationId()).isEqualTo(ORG);
        assertThat(link.getValue().getOccurrences()).isEqualTo(1);
    }

    @Test
    void locksTheDedupKeyBeforeLookingForTheLink() {
        when(linkRepository.findOpen(ORG, null, "cpu")).thenReturn(Optional.empty());
        when(automatedIncidents.openFromAlert(any(), any(), anyString(), any(), anyString(), any(), anyString(),
                anyString())).thenReturn(INCIDENT);

        processOne(firing("SEV1"));

        InOrder order = inOrder(linkRepository);
        order.verify(linkRepository).lockDedupKey(ORG + ":default:cpu");
        order.verify(linkRepository).findOpen(ORG, null, "cpu");
    }

    @Test
    void repeatedFiringUpdatesOccurrencesAndSeverity() {
        AlertIncidentLink link = openLink();
        when(automatedIncidents.isResolved(ORG, INCIDENT)).thenReturn(false);

        AlertResult result = processOne(firing("SEV2"));

        assertThat(result.action()).isEqualTo(AlertAction.UPDATED);
        assertThat(result.incidentId()).isEqualTo(INCIDENT);
        assertThat(link.getOccurrences()).isEqualTo(2);
        assertThat(link.getLastSeenAt()).isEqualTo(NOW);
        verify(automatedIncidents).updateSeverityFromAlert(ORG, INCIDENT, "SEV2", KEY.keyId());
        verify(automatedIncidents, never()).openFromAlert(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void firingAfterIncidentWasResolvedByAPersonOpensANewIncident() {
        AlertIncidentLink old = openLink();
        UUID newIncident = UUID.randomUUID();
        when(automatedIncidents.isResolved(ORG, INCIDENT)).thenReturn(true);
        when(automatedIncidents.openFromAlert(any(), any(), anyString(), any(), anyString(), any(), anyString(),
                anyString())).thenReturn(newIncident);

        AlertResult result = processOne(firing("SEV1"));

        assertThat(result.action()).isEqualTo(AlertAction.OPENED);
        assertThat(result.incidentId()).isEqualTo(newIncident);
        assertThat(old.getClosedAt()).isEqualTo(NOW);
        verify(linkRepository).saveAndFlush(old);
    }

    @Test
    void resolvedAlertResolvesIncidentAndClosesLink() {
        AlertIncidentLink link = openLink();
        when(automatedIncidents.resolveFromAlert(ORG, INCIDENT, KEY.keyId())).thenReturn(true);

        AlertResult result = processOne(resolved());

        assertThat(result.action()).isEqualTo(AlertAction.RESOLVED);
        assertThat(result.incidentId()).isEqualTo(INCIDENT);
        assertThat(link.getClosedAt()).isEqualTo(NOW);
    }

    @Test
    void resolvedAlertForIncidentAlreadyResolvedIsIgnoredButClosesLink() {
        AlertIncidentLink link = openLink();
        when(automatedIncidents.resolveFromAlert(ORG, INCIDENT, KEY.keyId())).thenReturn(false);

        AlertResult result = processOne(resolved());

        assertThat(result.action()).isEqualTo(AlertAction.IGNORED);
        assertThat(link.getClosedAt()).isEqualTo(NOW);
    }

    @Test
    void resolvedAlertWithoutLinkIsIgnored() {
        when(linkRepository.findOpen(ORG, null, "cpu")).thenReturn(Optional.empty());

        AlertResult result = processOne(resolved());

        assertThat(result.action()).isEqualTo(AlertAction.IGNORED);
        assertThat(result.incidentId()).isNull();
        verify(automatedIncidents, never()).resolveFromAlert(any(), any(), any());
    }

    @Test
    void invalidAlertIsRejectedWithoutTouchingIncidents() {
        MappedAlert invalid = new MappedAlert(0, null, "cpu", null, null, List.of(), null, "title ausente");

        AlertResult result = processOne(invalid);

        assertThat(result.action()).isEqualTo(AlertAction.REJECTED);
        assertThat(result.reason()).isEqualTo("title ausente");
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void failureInOneAlertDoesNotStopTheOthers() {
        MappedAlert first = new MappedAlert(0, "a", "boom", AlertStatus.FIRING, "SEV1", List.of(), null, null);
        MappedAlert second = new MappedAlert(1, "b", "ok", AlertStatus.RESOLVED, "SEV1", List.of(), null, null);
        when(linkRepository.findOpen(ORG, null, "boom")).thenThrow(new IllegalStateException("db down"));
        when(linkRepository.findOpen(ORG, null, "ok")).thenReturn(Optional.empty());

        WebhookResult result = processor.process(KEY, null, SOURCE_NAME, List.of(first, second));

        assertThat(result.received()).isEqualTo(2);
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.results().get(0).action()).isEqualTo(AlertAction.REJECTED);
        assertThat(result.results().get(0).reason()).isEqualTo("erro ao processar alerta");
        assertThat(result.results().get(1).action()).isEqualTo(AlertAction.IGNORED);
    }

    @Test
    void sourceIdIsPartOfTheLockKey() {
        UUID source = UUID.randomUUID();
        when(linkRepository.findOpen(eq(ORG), eq(source), eq("cpu"))).thenReturn(Optional.empty());

        processor.process(KEY, source, "Grafana", List.of(resolved()));

        verify(linkRepository).lockDedupKey(ORG + ":" + source + ":cpu");
    }
}
