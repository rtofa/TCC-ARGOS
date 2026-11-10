package br.com.argos.argos_api.delivery.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QualityGateResponse(
        boolean allowed,
        String status,
        String service,
        long openSev1Incidents,
        long openCriticalAlerts,
        String reason,
        List<BlockingIncident> blockingIncidents,
        Instant evaluatedAt) {

    public record BlockingIncident(
            UUID id,
            String title,
            String severity,
            String status,
            String source,
            List<String> affectedServices,
            Instant openedAt) {
    }
}
