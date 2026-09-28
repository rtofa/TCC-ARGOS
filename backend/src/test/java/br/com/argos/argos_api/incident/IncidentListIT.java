package br.com.argos.argos_api.incident;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IncidentListIT extends AbstractIncidentIT {

    private static final Instant DAY1 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant DAY2 = Instant.parse("2026-09-02T10:00:00Z");
    private static final Instant DAY3 = Instant.parse("2026-09-03T10:00:00Z");
    private static final Instant DAY4 = Instant.parse("2026-09-04T10:00:00Z");

    private UUID openSev1Day1;
    private UUID investigatingSev2Day2;
    private UUID resolvedSev1Day3;
    private UUID mitigatedSev3Day4;
    private UUID incidentOfB;

    @BeforeEach
    void insertIncidents() {
        openSev1Day1 = IncidentTestSupport.insertIncident(jdbc, orgA, adminA, "SEV1", "OPEN", DAY1, null, null);
        investigatingSev2Day2 = IncidentTestSupport.insertIncident(jdbc, orgA, adminA, "SEV2", "INVESTIGATING", DAY2, DAY2, null);
        resolvedSev1Day3 = IncidentTestSupport.insertIncident(jdbc, orgA, adminA, "SEV1", "RESOLVED", DAY3, DAY3, DAY3);
        mitigatedSev3Day4 = IncidentTestSupport.insertIncident(jdbc, orgA, adminA, "SEV3", "MITIGATED", DAY4, DAY4, null);
        incidentOfB = IncidentTestSupport.insertIncident(jdbc, orgB, adminB, "SEV1", "OPEN", DAY4, null, null);
    }

    @Test
    void listsOwnIncidentsNewestFirst() throws Exception {
        mockMvc.perform(get("/api/incidents").with(asViewerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(
                        mitigatedSev3Day4.toString(), resolvedSev1Day3.toString(),
                        investigatingSev2Day2.toString(), openSev1Day1.toString())))
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void executiveCanList() throws Exception {
        mockMvc.perform(get("/api/incidents").with(asExecutiveA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(4)));
    }

    @Test
    void filtersByOneOrMoreStatuses() throws Exception {
        mockMvc.perform(get("/api/incidents").with(asAdminA())
                        .param("status", "OPEN").param("status", "INVESTIGATING"))
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(
                        openSev1Day1.toString(), investigatingSev2Day2.toString())));
    }

    @Test
    void filtersBySeverity() throws Exception {
        mockMvc.perform(get("/api/incidents").with(asAdminA()).param("severity", "SEV1"))
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(
                        openSev1Day1.toString(), resolvedSev1Day3.toString())));
    }

    @Test
    void filtersByOpenedPeriodWithInclusiveStartAndExclusiveEnd() throws Exception {
        mockMvc.perform(get("/api/incidents").with(asAdminA())
                        .param("openedFrom", DAY2.toString()).param("openedTo", DAY4.toString()))
                .andExpect(jsonPath("$.content[*].id", contains(
                        resolvedSev1Day3.toString(), investigatingSev2Day2.toString())));
    }

    @Test
    void combinesFilters() throws Exception {
        mockMvc.perform(get("/api/incidents").with(asAdminA())
                        .param("severity", "SEV1").param("status", "RESOLVED")
                        .param("openedFrom", DAY2.toString()))
                .andExpect(jsonPath("$.content[*].id", contains(resolvedSev1Day3.toString())));
    }

    @Test
    void paginates() throws Exception {
        mockMvc.perform(get("/api/incidents").with(asAdminA()).param("page", "1").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(openSev1Day1.toString())))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void capsPageSizeAt100() throws Exception {
        mockMvc.perform(get("/api/incidents").with(asAdminA()).param("size", "500"))
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void emptyResultIsNotAnError() throws Exception {
        mockMvc.perform(get("/api/incidents").with(asAdminA()).param("severity", "SEV4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void rejectsUnknownFilterValues() throws Exception {
        mockMvc.perform(get("/api/incidents").with(asAdminA()).param("status", "FOO"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/incidents").with(asAdminA()).param("openedFrom", "ontem"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otherOrganizationSeesOnlyItsOwn() throws Exception {
        mockMvc.perform(get("/api/incidents").with(asAdminB()))
                .andExpect(jsonPath("$.content[*].id", contains(incidentOfB.toString())));
    }
}
