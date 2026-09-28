package br.com.argos.argos_api.incident.dto;

import br.com.argos.argos_api.incident.domain.Incident;
import br.com.argos.argos_api.incident.domain.IncidentSource;
import br.com.argos.argos_api.incident.domain.IncidentStatus;
import br.com.argos.argos_api.incident.domain.Severity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        String title,
        String description,
        Severity severity,
        IncidentStatus status,
        IncidentSource source,
        List<String> affectedServices,
        UUID assigneeId,
        UUID createdBy,
        Instant openedAt,
        Instant acknowledgedAt,
        Instant resolvedAt,
        Instant updatedAt,
        Long mttaSeconds,
        Long mttrSeconds) {

    public static IncidentResponse from(Incident incident) {
        return new IncidentResponse(
                incident.getId(),
                incident.getTitle(),
                incident.getDescription(),
                incident.getSeverity(),
                incident.getStatus(),
                incident.getSource(),
                List.copyOf(incident.getAffectedServices()),
                incident.getAssigneeId(),
                incident.getCreatedBy(),
                incident.getOpenedAt(),
                incident.getAcknowledgedAt(),
                incident.getResolvedAt(),
                incident.getUpdatedAt(),
                incident.mttaSeconds(),
                incident.mttrSeconds());
    }
}
