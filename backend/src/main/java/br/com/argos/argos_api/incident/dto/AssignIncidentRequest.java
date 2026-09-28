package br.com.argos.argos_api.incident.dto;

import java.util.UUID;

/**
 * A null assigneeId removes the current assignee.
 */
public record AssignIncidentRequest(UUID assigneeId) {
}
