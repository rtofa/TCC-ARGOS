package br.com.argos.argos_api.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable timeline entry of an incident. Only created through the static factories.
 */
@Entity
@Table(name = "incident_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IncidentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(insertable = false, updatable = false)
    private Long seq;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private EventType type;

    /** Null when the event was produced by the system (e.g. an alert). */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "api_key_id", updatable = false)
    private UUID apiKeyId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "old_value", updatable = false)
    private String oldValue;

    @Column(name = "new_value", updatable = false)
    private String newValue;

    @Column(updatable = false)
    private String body;

    private IncidentEvent(Incident incident, EventType type, UUID actorId, Instant occurredAt,
                          String oldValue, String newValue, String body) {
        this(incident, type, actorId, null, occurredAt, oldValue, newValue, body);
    }

    private IncidentEvent(Incident incident, EventType type, UUID actorId, UUID apiKeyId, Instant occurredAt,
                          String oldValue, String newValue, String body) {
        this.apiKeyId = apiKeyId;
        this.organizationId = incident.getOrganizationId();
        this.incidentId = incident.getId();
        this.type = type;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.body = body;
    }

    public static IncidentEvent opened(Incident incident, UUID actorId, Instant at) {
        return new IncidentEvent(incident, EventType.OPENED, actorId, at, null, null, null);
    }

    public static IncidentEvent statusChanged(Incident incident, UUID actorId, Instant at,
                                              IncidentStatus from, IncidentStatus to) {
        return new IncidentEvent(incident, EventType.STATUS_CHANGED, actorId, at, from.name(), to.name(), null);
    }

    public static IncidentEvent reopened(Incident incident, UUID actorId, Instant at,
                                         IncidentStatus from, IncidentStatus to) {
        return new IncidentEvent(incident, EventType.REOPENED, actorId, at, from.name(), to.name(), null);
    }

    public static IncidentEvent severityChanged(Incident incident, UUID actorId, Instant at,
                                                Severity from, Severity to) {
        return new IncidentEvent(incident, EventType.SEVERITY_CHANGED, actorId, at, from.name(), to.name(), null);
    }

    public static IncidentEvent assigneeChanged(Incident incident, UUID actorId, Instant at,
                                                UUID from, UUID to) {
        return new IncidentEvent(incident, EventType.ASSIGNEE_CHANGED, actorId, at,
                from != null ? from.toString() : null, to != null ? to.toString() : null, null);
    }

    public static IncidentEvent severityChangedByAlert(Incident incident, UUID apiKeyId, Instant at,
                                                       Severity from, Severity to) {
        return new IncidentEvent(incident, EventType.SEVERITY_CHANGED, null, apiKeyId, at, from.name(), to.name(), null);
    }

    public static IncidentEvent alertTriggered(Incident incident, UUID apiKeyId, Instant at,
                                               String dedupKey, String sourceName) {
        return new IncidentEvent(incident, EventType.ALERT_TRIGGERED, null, apiKeyId, at, null, dedupKey, sourceName);
    }

    public static IncidentEvent alertResolved(Incident incident, UUID apiKeyId, Instant at, IncidentStatus from) {
        return new IncidentEvent(incident, EventType.ALERT_RESOLVED, null, apiKeyId, at,
                from.name(), IncidentStatus.RESOLVED.name(), null);
    }

    public static IncidentEvent comment(Incident incident, UUID actorId, Instant at, String body) {
        return new IncidentEvent(incident, EventType.COMMENT, actorId, at, null, null, body);
    }
}
