package br.com.argos.argos_api.incident;

import br.com.argos.argos_api.incident.domain.EventType;
import br.com.argos.argos_api.incident.domain.Incident;
import br.com.argos.argos_api.incident.domain.IncidentEvent;
import br.com.argos.argos_api.incident.domain.IncidentEventRepository;
import br.com.argos.argos_api.incident.domain.IncidentRepository;
import br.com.argos.argos_api.incident.domain.IncidentSource;
import br.com.argos.argos_api.incident.domain.IncidentStatus;
import br.com.argos.argos_api.incident.domain.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutomatedIncidentsTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    @Mock
    private IncidentRepository incidentRepository;
    @Mock
    private IncidentEventRepository eventRepository;

    private AutomatedIncidents automatedIncidents;

    @BeforeEach
    void setUp() {
        automatedIncidents = new AutomatedIncidents(incidentRepository, eventRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void opensAlertIncidentInTheGivenOrganization() {
        when(incidentRepository.save(any(Incident.class))).then(returnsFirstArg());

        UUID id = automatedIncidents.openFromAlert(ORG, KEY, "CPU alta", "95%", "SEV1",
                List.of("checkout-api"), "cpu-checkout", "Formato padrão");

        ArgumentCaptor<Incident> incident = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(incident.capture());
        assertThat(incident.getValue().getId()).isEqualTo(id);
        assertThat(incident.getValue().getOrganizationId()).isEqualTo(ORG);
        assertThat(incident.getValue().getSource()).isEqualTo(IncidentSource.ALERT);
        assertThat(incident.getValue().getSeverity()).isEqualTo(Severity.SEV1);
        assertThat(incident.getValue().getOpenedAt()).isEqualTo(NOW);

        ArgumentCaptor<IncidentEvent> event = ArgumentCaptor.forClass(IncidentEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getType()).isEqualTo(EventType.ALERT_TRIGGERED);
        assertThat(event.getValue().getApiKeyId()).isEqualTo(KEY);
        assertThat(event.getValue().getNewValue()).isEqualTo("cpu-checkout");
    }

    @Test
    void truncatesLongTitles() {
        when(incidentRepository.save(any(Incident.class))).then(returnsFirstArg());

        automatedIncidents.openFromAlert(ORG, KEY, "x".repeat(300), null, "SEV3", null, "k", "s");

        ArgumentCaptor<Incident> incident = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(incident.capture());
        assertThat(incident.getValue().getTitle()).hasSize(200);
    }

    @Test
    void rejectsInvalidSeverity() {
        assertThatThrownBy(() -> automatedIncidents.openFromAlert(ORG, KEY, "t", null, "SEV9", null, "k", "s"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incidentOutsideTheOrganizationCountsAsResolved() {
        UUID id = UUID.randomUUID();
        when(incidentRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThat(automatedIncidents.isResolved(ORG, id)).isTrue();
    }

    @Test
    void reportsWhetherIncidentIsResolved() {
        Incident incident = alertIncident();

        assertThat(automatedIncidents.isResolved(ORG, incident.getId())).isFalse();
        incident.changeStatus(IncidentStatus.RESOLVED, UUID.randomUUID(), NOW);
        assertThat(automatedIncidents.isResolved(ORG, incident.getId())).isTrue();
    }

    @Test
    void updatesSeverityOnlyWhenItChanges() {
        Incident incident = alertIncident();

        automatedIncidents.updateSeverityFromAlert(ORG, incident.getId(), "SEV1", KEY);
        verify(eventRepository, never()).saveAll(anyList());

        automatedIncidents.updateSeverityFromAlert(ORG, incident.getId(), "SEV2", KEY);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IncidentEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(eventRepository).saveAll(events.capture());
        assertThat(events.getValue()).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo(EventType.SEVERITY_CHANGED);
            assertThat(event.getActorId()).isNull();
            assertThat(event.getApiKeyId()).isEqualTo(KEY);
        });
        assertThat(incident.getSeverity()).isEqualTo(Severity.SEV2);
    }

    @Test
    void resolvesOnlyWhenThereIsSomethingToResolve() {
        Incident incident = alertIncident();

        assertThat(automatedIncidents.resolveFromAlert(ORG, incident.getId(), KEY)).isTrue();
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(automatedIncidents.resolveFromAlert(ORG, incident.getId(), KEY)).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IncidentEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(eventRepository).saveAll(events.capture());
        assertThat(events.getValue()).extracting(IncidentEvent::getType).containsExactly(EventType.ALERT_RESOLVED);
    }

    @Test
    void resolvingIncidentOutsideTheOrganizationDoesNothing() {
        UUID id = UUID.randomUUID();
        when(incidentRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThat(automatedIncidents.resolveFromAlert(ORG, id, KEY)).isFalse();
    }

    private Incident alertIncident() {
        Incident incident = Incident.openFromAlert(ORG, "CPU alta", null, Severity.SEV1, List.of(), NOW.minusSeconds(60));
        when(incidentRepository.findByIdAndOrganizationId(incident.getId(), ORG)).thenReturn(Optional.of(incident));
        return incident;
    }
}
