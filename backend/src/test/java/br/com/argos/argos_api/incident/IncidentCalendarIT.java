package br.com.argos.argos_api.incident;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IncidentCalendarIT extends AbstractIntegrationTest {

    private void insertIncident(UUID orgId, UUID userId, String title, String severity, String status, Instant openedAt) {
        jdbc.update("""
                INSERT INTO incidents (id, organization_id, title, severity, status, source, affected_services,
                                       created_by, opened_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'MANUAL', ARRAY['checkout'], ?, ?, ?)
                """, UUID.randomUUID(), orgId, title, severity, status, userId,
                java.sql.Timestamp.from(openedAt), java.sql.Timestamp.from(openedAt));
    }

    @Test
    void calendarAggregatesByDayRespectsFiltersAndTenantIsolation() throws Exception {
        // 2026-11-10 in America/Sao_Paulo (-03:00): 03:00Z = 00:00 local, 23:00Z = 20:00 local
        Instant nov10Morning = Instant.parse("2026-11-10T12:00:00Z");
        Instant nov10Evening = Instant.parse("2026-11-10T23:30:00Z");
        Instant nov12Morning = Instant.parse("2026-11-12T15:00:00Z");

        insertIncident(orgA, adminA, "Erro leve dia 10", "SEV3", "OPEN", nov10Morning);
        insertIncident(orgA, adminA, "Queda critica dia 10", "SEV1", "RESOLVED", nov10Evening);
        insertIncident(orgA, adminA, "Falha dia 12", "SEV2", "OPEN", nov12Morning);
        insertIncident(orgB, adminB, "Incidente org B", "SEV1", "OPEN", nov12Morning);

        mockMvc.perform(get("/api/incidents/calendar")
                        .with(asViewerA())
                        .param("openedFrom", "2026-11-01T03:00:00Z")
                        .param("openedTo", "2026-12-01T03:00:00Z")
                        .param("tz", "America/Sao_Paulo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].date").value("2026-11-10"))
                .andExpect(jsonPath("$[0].count").value(2))
                .andExpect(jsonPath("$[0].highestSeverity").value("SEV1"))
                .andExpect(jsonPath("$[1].date").value("2026-11-12"))
                .andExpect(jsonPath("$[1].count").value(1))
                .andExpect(jsonPath("$[1].highestSeverity").value("SEV2"));

        // Status filter applied to calendar counts
        mockMvc.perform(get("/api/incidents/calendar")
                        .with(asViewerA())
                        .param("status", "OPEN")
                        .param("openedFrom", "2026-11-01T03:00:00Z")
                        .param("openedTo", "2026-12-01T03:00:00Z")
                        .param("tz", "America/Sao_Paulo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].count").value(1))
                .andExpect(jsonPath("$[0].highestSeverity").value("SEV3"));

        // Exact day filter via /api/incidents matches only Nov 10 in local time
        mockMvc.perform(get("/api/incidents")
                        .with(asViewerA())
                        .param("openedFrom", "2026-11-10T03:00:00Z")
                        .param("openedTo", "2026-11-11T03:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].title", contains("Queda critica dia 10", "Erro leve dia 10")));
    }

    @Test
    void calendarRejectsInvalidTimezoneOrTooLongRange() throws Exception {
        mockMvc.perform(get("/api/incidents/calendar")
                        .with(asViewerA())
                        .param("openedFrom", "2026-11-01T00:00:00Z")
                        .param("openedTo", "2026-12-01T00:00:00Z")
                        .param("tz", "Mars/Olympus"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/incidents/calendar")
                        .with(asViewerA())
                        .param("openedFrom", "2026-01-01T00:00:00Z")
                        .param("openedTo", "2026-06-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }
}
