package br.com.argos.argos_api.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Incident aggregate. Holds the lifecycle rules (acknowledgement, resolution, reopening)
 * and returns the timeline events each change produces; persisting them is up to the caller.
 */
@Entity
@Table(name = "incidents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean isNew = true;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 200)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private IncidentSource source;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "affected_services", nullable = false, columnDefinition = "text[]")
    private List<String> affectedServices = new ArrayList<>();

    @Column(name = "assignee_id")
    private UUID assigneeId;

    /** Null for incidents opened automatically (source ALERT). */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Incident open(UUID organizationId, UUID createdBy, String title, String description,
                                Severity severity, List<String> affectedServices, UUID assigneeId, Instant now) {
        Incident incident = new Incident();
        // Id assigned here so the OPENED event can reference it before the first flush.
        incident.id = UUID.randomUUID();
        incident.organizationId = organizationId;
        incident.createdBy = createdBy;
        incident.title = title.trim();
        incident.description = description;
        incident.severity = severity;
        incident.status = IncidentStatus.OPEN;
        incident.source = IncidentSource.MANUAL;
        incident.affectedServices = normalizeServices(affectedServices);
        incident.assigneeId = assigneeId;
        incident.openedAt = now;
        incident.updatedAt = now;
        if (assigneeId != null) {
            incident.acknowledgedAt = now;
        }
        return incident;
    }

    /**
     * Opens an incident on behalf of the system; the caller records the ALERT_TRIGGERED event.
     */
    public static Incident openFromAlert(UUID organizationId, String title, String description, Severity severity,
                                         List<String> affectedServices, Instant now) {
        Incident incident = open(organizationId, null, title, description, severity, affectedServices, null, now);
        incident.source = IncidentSource.ALERT;
        return incident;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public List<IncidentEvent> changeStatus(IncidentStatus newStatus, UUID actorId, Instant now) {
        if (newStatus == null || newStatus == status) {
            return List.of();
        }
        IncidentStatus previous = status;
        status = newStatus;
        if (newStatus != IncidentStatus.OPEN) {
            acknowledge(now);
        }
        if (newStatus == IncidentStatus.RESOLVED) {
            resolvedAt = now;
        }
        updatedAt = now;
        if (previous == IncidentStatus.RESOLVED) {
            resolvedAt = null;
            return List.of(IncidentEvent.reopened(this, actorId, now, previous, newStatus));
        }
        return List.of(IncidentEvent.statusChanged(this, actorId, now, previous, newStatus));
    }

    /**
     * Resolves the incident because its alert reported recovery. No-op if already resolved.
     */
    public List<IncidentEvent> resolveByAlert(UUID apiKeyId, Instant now) {
        if (status == IncidentStatus.RESOLVED) {
            return List.of();
        }
        IncidentStatus previous = status;
        status = IncidentStatus.RESOLVED;
        acknowledge(now);
        resolvedAt = now;
        updatedAt = now;
        return List.of(IncidentEvent.alertResolved(this, apiKeyId, now, previous));
    }

    public List<IncidentEvent> changeSeverityByAlert(Severity newSeverity, UUID apiKeyId, Instant now) {
        if (newSeverity == null || newSeverity == severity) {
            return List.of();
        }
        Severity previous = severity;
        severity = newSeverity;
        updatedAt = now;
        return List.of(IncidentEvent.severityChangedByAlert(this, apiKeyId, now, previous, newSeverity));
    }

    public List<IncidentEvent> changeSeverity(Severity newSeverity, UUID actorId, Instant now) {
        if (newSeverity == null || newSeverity == severity) {
            return List.of();
        }
        Severity previous = severity;
        severity = newSeverity;
        updatedAt = now;
        return List.of(IncidentEvent.severityChanged(this, actorId, now, previous, newSeverity));
    }

    /**
     * Sets or removes (null) the assignee. Assigning someone counts as acknowledgement.
     */
    public List<IncidentEvent> assign(UUID newAssigneeId, UUID actorId, Instant now) {
        if (Objects.equals(newAssigneeId, assigneeId)) {
            return List.of();
        }
        UUID previous = assigneeId;
        assigneeId = newAssigneeId;
        if (newAssigneeId != null) {
            acknowledge(now);
        }
        updatedAt = now;
        return List.of(IncidentEvent.assigneeChanged(this, actorId, now, previous, newAssigneeId));
    }

    /**
     * Edits descriptive fields; null keeps the current value. Produces no timeline event.
     */
    public void edit(String newTitle, String newDescription, List<String> newAffectedServices, Instant now) {
        boolean changed = false;
        if (newTitle != null && !newTitle.trim().equals(title)) {
            title = newTitle.trim();
            changed = true;
        }
        if (newDescription != null && !newDescription.equals(description)) {
            description = newDescription;
            changed = true;
        }
        if (newAffectedServices != null) {
            List<String> normalized = normalizeServices(newAffectedServices);
            if (!normalized.equals(affectedServices)) {
                affectedServices = normalized;
                changed = true;
            }
        }
        if (changed) {
            updatedAt = now;
        }
    }

    public void touch(Instant now) {
        updatedAt = now;
    }

    private void acknowledge(Instant now) {
        if (acknowledgedAt == null) {
            acknowledgedAt = now;
        }
    }

    public Long mttaSeconds() {
        return acknowledgedAt == null ? null : Duration.between(openedAt, acknowledgedAt).getSeconds();
    }

    public Long mttrSeconds() {
        return resolvedAt == null ? null : Duration.between(openedAt, resolvedAt).getSeconds();
    }

    static List<String> normalizeServices(List<String> services) {
        if (services == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String service : services) {
            if (service != null && !service.isBlank()) {
                unique.add(service.trim());
            }
        }
        return new ArrayList<>(unique);
    }
}
