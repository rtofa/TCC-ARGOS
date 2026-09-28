package br.com.argos.argos_api.incident;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Incident-specific test data; organizations, users and tokens come from
 * {@link br.com.argos.argos_api.support.TestFixtures}.
 */
final class IncidentTestSupport {

    private IncidentTestSupport() {
    }

    /**
     * Inserts an incident with controlled timestamps (list filters, metrics).
     */
    static UUID insertIncident(JdbcTemplate jdbc, UUID organizationId, UUID createdBy, String severity, String status,
                               Instant openedAt, Instant acknowledgedAt, Instant resolvedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO incidents (id, organization_id, title, severity, status, created_by,
                                               opened_at, acknowledged_at, resolved_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, organizationId, "Incidente " + severity + " " + status, severity, status, createdBy,
                utc(openedAt), utc(acknowledgedAt), utc(resolvedAt), utc(openedAt));
        return id;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
