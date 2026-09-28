package br.com.argos.argos_api.alerting.dto;

import java.time.Instant;
import java.util.UUID;

public record IncidentAlertLinkResponse(
        UUID sourceId,
        String sourceName,
        String dedupKey,
        int occurrences,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant closedAt) {
}
