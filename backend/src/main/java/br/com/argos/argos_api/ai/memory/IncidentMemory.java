package br.com.argos.argos_api.ai.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Long-term (episodic) memory: what the agent concluded about earlier incidents of the same organization. The agent
 * reaches it through a tool, so it only pays the tokens when the history is relevant ("this already happened").
 */
@Component
public class IncidentMemory {

    public record PastIncident(UUID incidentId, String title, Instant openedAt, Instant resolvedAt, String rootCause,
                               String confidence, String nextSteps) {
    }

    private final JdbcTemplate jdbc;

    public IncidentMemory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Latest completed investigation of each earlier incident that affected {@code service} (any, when blank). */
    public List<PastIncident> similar(UUID organizationId, UUID excludeIncidentId, String service, int limit) {
        String wanted = service == null ? "" : service.trim();
        return jdbc.query("""
                SELECT * FROM (
                    SELECT DISTINCT ON (a.incident_id)
                           a.incident_id, i.title, i.opened_at, i.resolved_at, a.finished_at,
                           a.report -> 'rootCause' ->> 'summary'    AS root_cause,
                           a.report -> 'rootCause' ->> 'confidence' AS confidence,
                           a.report ->> 'nextSteps'                  AS next_steps
                    FROM ai_investigations a
                    JOIN incidents i ON i.id = a.incident_id AND i.organization_id = a.organization_id
                    WHERE a.organization_id = ?
                      AND a.status = 'COMPLETED'
                      AND a.incident_id <> ?
                      AND (? = '' OR EXISTS (SELECT 1 FROM unnest(i.affected_services) s WHERE lower(s) = lower(?)))
                    ORDER BY a.incident_id, a.finished_at DESC
                ) latest
                ORDER BY finished_at DESC
                LIMIT ?
                """, (rs, i) -> new PastIncident(rs.getObject("incident_id", UUID.class), rs.getString("title"),
                        instant(rs.getTimestamp("opened_at")), instant(rs.getTimestamp("resolved_at")),
                        rs.getString("root_cause"), rs.getString("confidence"), rs.getString("next_steps")),
                organizationId, excludeIncidentId, wanted, wanted, Math.max(1, Math.min(limit, 5)));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
