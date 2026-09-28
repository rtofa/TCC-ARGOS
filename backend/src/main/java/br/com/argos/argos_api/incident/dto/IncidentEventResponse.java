package br.com.argos.argos_api.incident.dto;

import br.com.argos.argos_api.incident.domain.EventType;
import br.com.argos.argos_api.incident.domain.IncidentEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * actorType is SYSTEM when the event has no human author (e.g. produced by an alert via apiKeyId).
 */
public record IncidentEventResponse(
        UUID id,
        EventType type,
        UUID actorId,
        String actorType,
        UUID apiKeyId,
        Instant occurredAt,
        String oldValue,
        String newValue,
        String body) {

    public static IncidentEventResponse from(IncidentEvent event) {
        return new IncidentEventResponse(event.getId(), event.getType(), event.getActorId(),
                event.getActorId() == null ? "SYSTEM" : "USER", event.getApiKeyId(), event.getOccurredAt(),
                event.getOldValue(), event.getNewValue(), event.getBody());
    }
}
