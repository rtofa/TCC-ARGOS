package br.com.argos.argos_api.incident;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after an incident is created, manually or from an alert. Listeners that do slow work (such as the AI
 * investigation) should react after the transaction commits.
 *
 * @param source "MANUAL" or "ALERT"
 */
public record IncidentOpened(UUID organizationId, UUID incidentId, String source, Instant openedAt) {
}
