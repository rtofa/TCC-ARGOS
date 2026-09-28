package br.com.argos.argos_api.incident;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IncidentMetricsIT extends AbstractIncidentIT {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-30T00:00:00Z");
    private static final Instant T = Instant.parse("2026-09-10T10:00:00Z");

    @BeforeEach
    void insertIncidents() {
        // SEV1: acknowledged after 10 and 20 min; resolved after 60 min and still open.
        IncidentTestSupport.insertIncident(jdbc, orgA, adminA, "SEV1", "RESOLVED",
                T, T.plusSeconds(600), T.plusSeconds(3600));
        IncidentTestSupport.insertIncident(jdbc, orgA, adminA, "SEV1", "INVESTIGATING",
                T, T.plusSeconds(1200), null);
        // SEV2: never acknowledged.
        IncidentTestSupport.insertIncident(jdbc, orgA, adminA, "SEV2", "OPEN", T, null, null);
        // Outside the period and from another organization: must be ignored.
        IncidentTestSupport.insertIncident(jdbc, orgA, adminA, "SEV1", "RESOLVED",
                Instant.parse("2026-08-01T10:00:00Z"), Instant.parse("2026-08-01T10:00:01Z"),
                Instant.parse("2026-08-01T10:00:02Z"));
        IncidentTestSupport.insertIncident(jdbc, orgB, adminB, "SEV1", "RESOLVED", T, T.plusSeconds(1), T.plusSeconds(2));
    }

    @Test
    void averagesOnlyIncidentsWithTheRespectiveTimestamp() throws Exception {
        mockMvc.perform(get("/api/incidents/metrics").with(asViewerA())
                        .param("openedFrom", FROM.toString()).param("openedTo", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openedFrom").value(FROM.toString()))
                .andExpect(jsonPath("$.overall.count").value(3))
                .andExpect(jsonPath("$.overall.mttaSeconds").value(900))
                .andExpect(jsonPath("$.overall.mttrSeconds").value(3600))
                .andExpect(jsonPath("$.bySeverity").doesNotExist());
    }

    @Test
    void groupsBySeverity() throws Exception {
        mockMvc.perform(get("/api/incidents/metrics").with(asExecutiveA())
                        .param("openedFrom", FROM.toString()).param("openedTo", TO.toString())
                        .param("groupBy", "severity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bySeverity", hasSize(2)))
                .andExpect(jsonPath("$.bySeverity[0].severity").value("SEV1"))
                .andExpect(jsonPath("$.bySeverity[0].count").value(2))
                .andExpect(jsonPath("$.bySeverity[0].mttaSeconds").value(900))
                .andExpect(jsonPath("$.bySeverity[0].mttrSeconds").value(3600))
                .andExpect(jsonPath("$.bySeverity[1].severity").value("SEV2"))
                .andExpect(jsonPath("$.bySeverity[1].count").value(1))
                .andExpect(jsonPath("$.bySeverity[1].mttaSeconds").doesNotExist())
                .andExpect(jsonPath("$.bySeverity[1].mttrSeconds").doesNotExist());
    }

    @Test
    void emptyPeriodHasZeroCountAndNoAverages() throws Exception {
        mockMvc.perform(get("/api/incidents/metrics").with(asAdminA())
                        .param("openedFrom", "2025-01-01T00:00:00Z").param("openedTo", "2025-02-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall.count").value(0))
                .andExpect(jsonPath("$.overall.mttaSeconds").doesNotExist())
                .andExpect(jsonPath("$.overall.mttrSeconds").doesNotExist());
    }

    @Test
    void otherOrganizationOnlySeesItsOwnNumbers() throws Exception {
        mockMvc.perform(get("/api/incidents/metrics").with(asAdminB())
                        .param("openedFrom", FROM.toString()).param("openedTo", TO.toString()))
                .andExpect(jsonPath("$.overall.count").value(1))
                .andExpect(jsonPath("$.overall.mttrSeconds").value(2));
    }

    @Test
    void rejectsInvalidPeriodOrGrouping() throws Exception {
        mockMvc.perform(get("/api/incidents/metrics").with(asAdminA())
                        .param("openedFrom", TO.toString()).param("openedTo", FROM.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/incidents/metrics").with(asAdminA()).param("groupBy", "status"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void usesLast30DaysByDefault() throws Exception {
        mockMvc.perform(get("/api/incidents/metrics").with(asAdminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall.count").isNumber());
    }
}
