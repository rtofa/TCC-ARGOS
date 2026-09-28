package br.com.argos.argos_api.incident;

import br.com.argos.argos_api.identity.OrganizationMembers;
import br.com.argos.argos_api.incident.domain.EventType;
import br.com.argos.argos_api.incident.domain.Incident;
import br.com.argos.argos_api.incident.domain.IncidentEvent;
import br.com.argos.argos_api.incident.domain.IncidentEventRepository;
import br.com.argos.argos_api.incident.domain.IncidentRepository;
import br.com.argos.argos_api.incident.domain.IncidentStatus;
import br.com.argos.argos_api.incident.domain.MetricsRow;
import br.com.argos.argos_api.incident.domain.Severity;
import br.com.argos.argos_api.incident.domain.SeverityMetricsRow;
import br.com.argos.argos_api.incident.dto.IncidentMetricsResponse;
import br.com.argos.argos_api.incident.dto.IncidentMetricsResponse.MetricsBucket;
import br.com.argos.argos_api.incident.dto.IncidentMetricsResponse.SeverityMetricsBucket;
import br.com.argos.argos_api.incident.dto.AssignIncidentRequest;
import br.com.argos.argos_api.incident.dto.CommentRequest;
import br.com.argos.argos_api.incident.dto.CreateIncidentRequest;
import br.com.argos.argos_api.incident.dto.IncidentEventResponse;
import br.com.argos.argos_api.incident.dto.IncidentResponse;
import br.com.argos.argos_api.incident.dto.UpdateIncidentRequest;
import br.com.argos.argos_api.shared.security.AuthenticatedUser;
import br.com.argos.argos_api.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    @Mock
    private IncidentRepository incidentRepository;
    @Mock
    private IncidentEventRepository eventRepository;
    @Mock
    private OrganizationMembers organizationMembers;
    @Mock
    private CurrentUser currentUser;

    private IncidentService service;

    @BeforeEach
    void setUp() {
        service = new IncidentService(incidentRepository, eventRepository, organizationMembers, currentUser,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(currentUser.get()).thenReturn(new AuthenticatedUser(USER, ORG));
    }

    @Test
    void createUsesAuthenticatedTenantAndRecordsOpenedEvent() {
        when(incidentRepository.save(any(Incident.class))).then(returnsFirstArg());

        IncidentResponse response = service.create(new CreateIncidentRequest(
                "Checkout fora do ar", null, Severity.SEV1, List.of("checkout-api"), null));

        ArgumentCaptor<Incident> incident = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(incident.capture());
        assertThat(incident.getValue().getOrganizationId()).isEqualTo(ORG);
        assertThat(incident.getValue().getCreatedBy()).isEqualTo(USER);
        assertThat(response.status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(response.openedAt()).isEqualTo(NOW);

        ArgumentCaptor<IncidentEvent> event = ArgumentCaptor.forClass(IncidentEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getType()).isEqualTo(EventType.OPENED);
        assertThat(event.getValue().getActorId()).isEqualTo(USER);
    }

    @Test
    void createRejectsAssigneeFromAnotherOrganization() {
        UUID outsider = UUID.randomUUID();
        when(organizationMembers.isMember(ORG, outsider)).thenReturn(false);

        assertThatThrownBy(() -> service.create(new CreateIncidentRequest(
                "t", null, Severity.SEV2, null, outsider)))
                .isInstanceOf(InvalidAssigneeException.class);
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void getOfIncidentFromAnotherTenantIsNotFound() {
        UUID id = UUID.randomUUID();
        when(incidentRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(IncidentNotFoundException.class);
    }

    @Test
    void updateOfMissingIncidentIsNotFound() {
        UUID id = UUID.randomUUID();
        when(incidentRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id,
                new UpdateIncidentRequest(null, null, null, IncidentStatus.RESOLVED, null)))
                .isInstanceOf(IncidentNotFoundException.class);
    }

    @Test
    void updateWithoutFieldsIsRejected() {
        assertThatThrownBy(() -> service.update(UUID.randomUUID(),
                new UpdateIncidentRequest(null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateSavesEventsProducedByTheIncident() {
        Incident incident = existingIncident();

        IncidentResponse response = service.update(incident.getId(),
                new UpdateIncidentRequest(null, null, Severity.SEV2, IncidentStatus.INVESTIGATING, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IncidentEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(eventRepository).saveAll(events.capture());
        assertThat(events.getValue()).extracting(IncidentEvent::getType)
                .containsExactlyInAnyOrder(EventType.STATUS_CHANGED, EventType.SEVERITY_CHANGED);
        assertThat(response.status()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(response.acknowledgedAt()).isEqualTo(NOW);
    }

    @Test
    void assignValidatesMembershipAndRecordsEvent() {
        Incident incident = existingIncident();
        UUID member = UUID.randomUUID();
        when(organizationMembers.isMember(ORG, member)).thenReturn(true);

        IncidentResponse response = service.assign(incident.getId(), new AssignIncidentRequest(member));

        assertThat(response.assigneeId()).isEqualTo(member);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IncidentEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(eventRepository).saveAll(events.capture());
        assertThat(events.getValue()).extracting(IncidentEvent::getType)
                .containsExactly(EventType.ASSIGNEE_CHANGED);
    }

    @Test
    void assignRejectsNonMember() {
        Incident incident = existingIncident();
        UUID outsider = UUID.randomUUID();
        when(organizationMembers.isMember(ORG, outsider)).thenReturn(false);

        assertThatThrownBy(() -> service.assign(incident.getId(), new AssignIncidentRequest(outsider)))
                .isInstanceOf(InvalidAssigneeException.class);
        verify(eventRepository, never()).saveAll(anyList());
    }

    @Test
    void unassignDoesNotCheckMembership() {
        Incident incident = existingIncident();

        service.assign(incident.getId(), new AssignIncidentRequest(null));

        verify(organizationMembers, never()).isMember(any(), any());
    }

    @Test
    void commentRecordsCommentEventAndTouchesIncident() {
        Incident incident = existingIncident();
        when(eventRepository.save(any(IncidentEvent.class))).then(returnsFirstArg());

        IncidentEventResponse response = service.comment(incident.getId(), new CommentRequest("Rollback iniciado"));

        assertThat(response.type()).isEqualTo(EventType.COMMENT);
        assertThat(response.body()).isEqualTo("Rollback iniciado");
        assertThat(response.actorId()).isEqualTo(USER);
        assertThat(response.occurredAt()).isEqualTo(NOW);
        assertThat(incident.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void commentOnIncidentFromAnotherTenantIsNotFound() {
        UUID id = UUID.randomUUID();
        when(incidentRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.comment(id, new CommentRequest("x")))
                .isInstanceOf(IncidentNotFoundException.class);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void timelineReturnsEventsInRepositoryOrder() {
        Incident incident = existingIncident();
        IncidentEvent opened = IncidentEvent.opened(incident, USER, NOW.minusSeconds(600));
        IncidentEvent comment = IncidentEvent.comment(incident, USER, NOW, "ok");
        when(eventRepository.findByIncidentIdAndOrganizationIdOrderByOccurredAtAscSeqAsc(incident.getId(), ORG))
                .thenReturn(List.of(opened, comment));

        List<IncidentEventResponse> timeline = service.timeline(incident.getId());

        assertThat(timeline).extracting(IncidentEventResponse::type)
                .containsExactly(EventType.OPENED, EventType.COMMENT);
    }

    @Test
    void timelineOfIncidentFromAnotherTenantIsNotFound() {
        UUID id = UUID.randomUUID();
        when(incidentRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.timeline(id)).isInstanceOf(IncidentNotFoundException.class);
    }

    record Row(long getCount, Long getMtta, Long getMttr) implements MetricsRow {
    }

    record SeverityRow(String getSeverity, long getCount, Long getMtta, Long getMttr) implements SeverityMetricsRow {
    }

    @Test
    void metricsDefaultToLast30Days() {
        Instant from = NOW.minus(30, ChronoUnit.DAYS);
        when(incidentRepository.aggregateMetrics(ORG, from, NOW)).thenReturn(new Row(3, 600L, 5400L));

        IncidentMetricsResponse response = service.metrics(null, null, null);

        assertThat(response.openedFrom()).isEqualTo(from);
        assertThat(response.openedTo()).isEqualTo(NOW);
        assertThat(response.overall()).isEqualTo(new MetricsBucket(3, 600L, 5400L));
        assertThat(response.bySeverity()).isNull();
    }

    @Test
    void metricsGroupedBySeverity() {
        Instant from = NOW.minus(7, ChronoUnit.DAYS);
        when(incidentRepository.aggregateMetrics(ORG, from, NOW)).thenReturn(new Row(2, 300L, null));
        when(incidentRepository.aggregateMetricsBySeverity(ORG, from, NOW))
                .thenReturn(List.of(new SeverityRow("SEV1", 2, 300L, null)));

        IncidentMetricsResponse response = service.metrics(from, NOW, "severity");

        assertThat(response.bySeverity())
                .containsExactly(new SeverityMetricsBucket(Severity.SEV1, 2, 300L, null));
    }

    @Test
    void metricsRejectInvalidPeriodAndGrouping() {
        assertThatThrownBy(() -> service.metrics(NOW, NOW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.metrics(NOW, NOW.minusSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.metrics(null, null, "status"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Incident existingIncident() {
        Incident incident = Incident.open(ORG, USER, "Checkout fora do ar", null, Severity.SEV1,
                List.of(), null, NOW.minusSeconds(600));
        when(incidentRepository.findByIdAndOrganizationId(incident.getId(), ORG)).thenReturn(Optional.of(incident));
        return incident;
    }
}
