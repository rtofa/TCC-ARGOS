package br.com.argos.argos_api.incident.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ASSIGNEE = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");
    private static final Instant T12 = Instant.parse("2026-09-26T10:12:00Z");
    private static final Instant T90 = Instant.parse("2026-09-26T11:30:00Z");

    private Incident newIncident() {
        return Incident.open(ORG, ACTOR, "  Checkout fora do ar ", "Erro 500", Severity.SEV1,
                List.of("checkout-api"), null, T0);
    }

    @Test
    void opensWithStatusOpenAndNoAcknowledgement() {
        Incident incident = newIncident();

        assertThat(incident.getId()).isNotNull();
        assertThat(incident.getTitle()).isEqualTo("Checkout fora do ar");
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getSource()).isEqualTo(IncidentSource.MANUAL);
        assertThat(incident.getOpenedAt()).isEqualTo(T0);
        assertThat(incident.getUpdatedAt()).isEqualTo(T0);
        assertThat(incident.getAcknowledgedAt()).isNull();
        assertThat(incident.mttaSeconds()).isNull();
        assertThat(incident.mttrSeconds()).isNull();
    }

    @Test
    void openingWithAssigneeCountsAsAcknowledged() {
        Incident incident = Incident.open(ORG, ACTOR, "t", null, Severity.SEV2, null, ASSIGNEE, T0);

        assertThat(incident.getAcknowledgedAt()).isEqualTo(T0);
        assertThat(incident.mttaSeconds()).isZero();
    }

    @Test
    void normalizesAffectedServices() {
        Incident incident = Incident.open(ORG, ACTOR, "t", null, Severity.SEV3,
                List.of(" checkout-api ", "", "checkout-api", "payment-api"), null, T0);

        assertThat(incident.getAffectedServices()).containsExactly("checkout-api", "payment-api");
    }

    @Test
    void leavingOpenAcknowledgesOnlyOnce() {
        Incident incident = newIncident();

        List<IncidentEvent> events = incident.changeStatus(IncidentStatus.INVESTIGATING, ACTOR, T12);
        incident.changeStatus(IncidentStatus.OPEN, ACTOR, T12.plusSeconds(60));
        incident.changeStatus(IncidentStatus.MITIGATED, ACTOR, T12.plusSeconds(120));

        assertThat(incident.getAcknowledgedAt()).isEqualTo(T12);
        assertThat(incident.mttaSeconds()).isEqualTo(720);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo(EventType.STATUS_CHANGED);
            assertThat(event.getOldValue()).isEqualTo("OPEN");
            assertThat(event.getNewValue()).isEqualTo("INVESTIGATING");
            assertThat(event.getActorId()).isEqualTo(ACTOR);
            assertThat(event.getOccurredAt()).isEqualTo(T12);
            assertThat(event.getIncidentId()).isEqualTo(incident.getId());
            assertThat(event.getOrganizationId()).isEqualTo(ORG);
        });
        assertThat(incident.getUpdatedAt()).isEqualTo(T12.plusSeconds(120));
    }

    @Test
    void sameStatusProducesNoEventAndNoChange() {
        Incident incident = newIncident();

        List<IncidentEvent> events = incident.changeStatus(IncidentStatus.OPEN, ACTOR, T12);

        assertThat(events).isEmpty();
        assertThat(incident.getAcknowledgedAt()).isNull();
        assertThat(incident.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void resolvingRecordsResolutionAndMttr() {
        Incident incident = newIncident();
        incident.changeStatus(IncidentStatus.INVESTIGATING, ACTOR, T12);

        List<IncidentEvent> events = incident.changeStatus(IncidentStatus.RESOLVED, ACTOR, T90);

        assertThat(incident.getResolvedAt()).isEqualTo(T90);
        assertThat(incident.mttrSeconds()).isEqualTo(5400);
        assertThat(events).extracting(IncidentEvent::getType).containsExactly(EventType.STATUS_CHANGED);
    }

    @Test
    void resolvingDirectlyFromOpenAcknowledgesAtResolution() {
        Incident incident = newIncident();

        incident.changeStatus(IncidentStatus.RESOLVED, ACTOR, T90);

        assertThat(incident.getAcknowledgedAt()).isEqualTo(T90);
        assertThat(incident.getResolvedAt()).isEqualTo(T90);
    }

    @Test
    void reopeningClearsResolutionKeepsAcknowledgementAndEmitsReopened() {
        Incident incident = newIncident();
        incident.changeStatus(IncidentStatus.INVESTIGATING, ACTOR, T12);
        incident.changeStatus(IncidentStatus.RESOLVED, ACTOR, T90);

        List<IncidentEvent> events = incident.changeStatus(IncidentStatus.INVESTIGATING, ACTOR, T90.plusSeconds(60));

        assertThat(incident.getResolvedAt()).isNull();
        assertThat(incident.mttrSeconds()).isNull();
        assertThat(incident.getAcknowledgedAt()).isEqualTo(T12);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo(EventType.REOPENED);
            assertThat(event.getOldValue()).isEqualTo("RESOLVED");
            assertThat(event.getNewValue()).isEqualTo("INVESTIGATING");
        });
    }

    @Test
    void mttrUsesFinalResolutionAfterReopening() {
        Incident incident = newIncident();
        incident.changeStatus(IncidentStatus.RESOLVED, ACTOR, T12);
        incident.changeStatus(IncidentStatus.INVESTIGATING, ACTOR, T12.plusSeconds(60));

        incident.changeStatus(IncidentStatus.RESOLVED, ACTOR, T90);

        assertThat(incident.mttrSeconds()).isEqualTo(5400);
    }

    @Test
    void assigningWhileOpenAcknowledges() {
        Incident incident = newIncident();

        List<IncidentEvent> events = incident.assign(ASSIGNEE, ACTOR, T12);

        assertThat(incident.getAssigneeId()).isEqualTo(ASSIGNEE);
        assertThat(incident.getAcknowledgedAt()).isEqualTo(T12);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo(EventType.ASSIGNEE_CHANGED);
            assertThat(event.getOldValue()).isNull();
            assertThat(event.getNewValue()).isEqualTo(ASSIGNEE.toString());
        });
    }

    @Test
    void assigningSameAssigneeProducesNoEvent() {
        Incident incident = newIncident();
        incident.assign(ASSIGNEE, ACTOR, T12);

        assertThat(incident.assign(ASSIGNEE, ACTOR, T90)).isEmpty();
    }

    @Test
    void removingAssigneeKeepsAcknowledgement() {
        Incident incident = newIncident();
        incident.assign(ASSIGNEE, ACTOR, T12);

        List<IncidentEvent> events = incident.assign(null, ACTOR, T90);

        assertThat(incident.getAssigneeId()).isNull();
        assertThat(incident.getAcknowledgedAt()).isEqualTo(T12);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getOldValue()).isEqualTo(ASSIGNEE.toString());
            assertThat(event.getNewValue()).isNull();
        });
    }

    @Test
    void changingSeverityEmitsEventWithOldAndNewValues() {
        Incident incident = newIncident();

        List<IncidentEvent> events = incident.changeSeverity(Severity.SEV3, ACTOR, T12);

        assertThat(incident.getSeverity()).isEqualTo(Severity.SEV3);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo(EventType.SEVERITY_CHANGED);
            assertThat(event.getOldValue()).isEqualTo("SEV1");
            assertThat(event.getNewValue()).isEqualTo("SEV3");
        });
        assertThat(incident.changeSeverity(Severity.SEV3, ACTOR, T90)).isEmpty();
    }

    @Test
    void editingTextFieldsUpdatesWithoutEvents() {
        Incident incident = newIncident();

        incident.edit(" Novo título ", "Nova descrição", List.of("payment-api"), T12);

        assertThat(incident.getTitle()).isEqualTo("Novo título");
        assertThat(incident.getDescription()).isEqualTo("Nova descrição");
        assertThat(incident.getAffectedServices()).containsExactly("payment-api");
        assertThat(incident.getUpdatedAt()).isEqualTo(T12);
    }

    @Test
    void openingFromAlertHasNoHumanCreator() {
        UUID apiKey = UUID.randomUUID();

        Incident incident = Incident.openFromAlert(ORG, "CPU alta", null, Severity.SEV1, List.of("checkout-api"), T0);
        IncidentEvent event = IncidentEvent.alertTriggered(incident, apiKey, T0, "cpu-checkout", "Grafana");

        assertThat(incident.getSource()).isEqualTo(IncidentSource.ALERT);
        assertThat(incident.getCreatedBy()).isNull();
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getAcknowledgedAt()).isNull();
        assertThat(event.getType()).isEqualTo(EventType.ALERT_TRIGGERED);
        assertThat(event.getActorId()).isNull();
        assertThat(event.getApiKeyId()).isEqualTo(apiKey);
        assertThat(event.getNewValue()).isEqualTo("cpu-checkout");
        assertThat(event.getBody()).isEqualTo("Grafana");
    }

    @Test
    void resolvingByAlertRecordsAlertResolvedEvent() {
        UUID apiKey = UUID.randomUUID();
        Incident incident = Incident.openFromAlert(ORG, "CPU alta", null, Severity.SEV1, null, T0);
        incident.changeStatus(IncidentStatus.INVESTIGATING, ACTOR, T12);

        List<IncidentEvent> events = incident.resolveByAlert(apiKey, T90);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getResolvedAt()).isEqualTo(T90);
        assertThat(incident.getAcknowledgedAt()).isEqualTo(T12);
        assertThat(incident.mttrSeconds()).isEqualTo(5400);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo(EventType.ALERT_RESOLVED);
            assertThat(event.getOldValue()).isEqualTo("INVESTIGATING");
            assertThat(event.getNewValue()).isEqualTo("RESOLVED");
            assertThat(event.getActorId()).isNull();
            assertThat(event.getApiKeyId()).isEqualTo(apiKey);
        });
    }

    @Test
    void resolvingByAlertDirectlyFromOpenAcknowledgesAtResolution() {
        Incident incident = Incident.openFromAlert(ORG, "CPU alta", null, Severity.SEV1, null, T0);

        incident.resolveByAlert(UUID.randomUUID(), T90);

        assertThat(incident.getAcknowledgedAt()).isEqualTo(T90);
    }

    @Test
    void resolvingByAlertWhenAlreadyResolvedDoesNothing() {
        Incident incident = Incident.openFromAlert(ORG, "CPU alta", null, Severity.SEV1, null, T0);
        incident.changeStatus(IncidentStatus.RESOLVED, ACTOR, T12);

        assertThat(incident.resolveByAlert(UUID.randomUUID(), T90)).isEmpty();
        assertThat(incident.getResolvedAt()).isEqualTo(T12);
    }

    @Test
    void severityChangeBySystemHasNoActor() {
        Incident incident = newIncident();

        List<IncidentEvent> events = incident.changeSeverity(Severity.SEV2, null, T12);

        assertThat(events).singleElement().satisfies(event -> assertThat(event.getActorId()).isNull());
    }

    @Test
    void editingWithNullsKeepsCurrentValues() {
        Incident incident = newIncident();

        incident.edit(null, null, null, T12);

        assertThat(incident.getTitle()).isEqualTo("Checkout fora do ar");
        assertThat(incident.getDescription()).isEqualTo("Erro 500");
        assertThat(incident.getAffectedServices()).containsExactly("checkout-api");
        assertThat(incident.getUpdatedAt()).isEqualTo(T0);
    }
}
