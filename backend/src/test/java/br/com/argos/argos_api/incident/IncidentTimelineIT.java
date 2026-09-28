package br.com.argos.argos_api.incident;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IncidentTimelineIT extends AbstractIncidentIT {

    private String createIncident() throws Exception {
        String body = mockMvc.perform(post("/api/incidents").with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Checkout fora do ar\",\"severity\":\"SEV1\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void patchIncident(String id, String json) throws Exception {
        mockMvc.perform(patch("/api/incidents/{id}", id).with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    private void comment(String id, RequestPostProcessor user, String body, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/incidents/{id}/comments", id).with(user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void recordsEveryChangeInChronologicalOrder() throws Exception {
        String id = createIncident();
        patchIncident(id, "{\"status\":\"INVESTIGATING\"}");
        patchIncident(id, "{\"severity\":\"SEV2\"}");
        mockMvc.perform(put("/api/incidents/{id}/assignee", id).with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":\"" + adminA + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/incidents/{id}/comments", id).with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Rollback iniciado\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("COMMENT"))
                .andExpect(jsonPath("$.actorId").value(adminA.toString()));

        mockMvc.perform(get("/api/incidents/{id}/timeline", id).with(asViewerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", contains(
                        "OPENED", "STATUS_CHANGED", "SEVERITY_CHANGED", "ASSIGNEE_CHANGED", "COMMENT")))
                .andExpect(jsonPath("$[0].actorId").value(editorA.toString()))
                .andExpect(jsonPath("$[1].oldValue").value("OPEN"))
                .andExpect(jsonPath("$[1].newValue").value("INVESTIGATING"))
                .andExpect(jsonPath("$[2].oldValue").value("SEV1"))
                .andExpect(jsonPath("$[2].newValue").value("SEV2"))
                .andExpect(jsonPath("$[3].newValue").value(adminA.toString()))
                .andExpect(jsonPath("$[4].body").value("Rollback iniciado"))
                .andExpect(jsonPath("$[4].actorId").value(adminA.toString()));
    }

    @Test
    void sameStatusAddsNothingAndReopeningIsRecorded() throws Exception {
        String id = createIncident();
        patchIncident(id, "{\"status\":\"OPEN\"}");
        patchIncident(id, "{\"status\":\"RESOLVED\"}");
        patchIncident(id, "{\"status\":\"INVESTIGATING\"}");

        mockMvc.perform(get("/api/incidents/{id}/timeline", id).with(asAdminA()))
                .andExpect(jsonPath("$[*].type", contains("OPENED", "STATUS_CHANGED", "REOPENED")));
    }

    @Test
    void rejectsInvalidComments() throws Exception {
        String id = createIncident();

        comment(id, asEditorA(), "", 400);
        comment(id, asEditorA(), "   ", 400);
        comment(id, asEditorA(), "x".repeat(5001), 400);
        comment(id, asEditorA(), "x".repeat(5000), 201);
    }

    @Test
    void viewerAndExecutiveCannotComment() throws Exception {
        String id = createIncident();

        comment(id, asViewerA(), "oi", 403);
        comment(id, asExecutiveA(), "oi", 403);
        mockMvc.perform(get("/api/incidents/{id}/timeline", id).with(asExecutiveA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void otherOrganizationSeesNotFound() throws Exception {
        String id = createIncident();

        mockMvc.perform(get("/api/incidents/{id}/timeline", id).with(asAdminB()))
                .andExpect(status().isNotFound());
        comment(id, asAdminB(), "oi", 404);
    }

    @Test
    void timelineIsAppendOnly() throws Exception {
        String id = createIncident();
        String eventId = JsonPath.read(mockMvc.perform(get("/api/incidents/{id}/timeline", id).with(asAdminA()))
                .andReturn().getResponse().getContentAsString(), "$[0].id");

        int deleteStatus = mockMvc.perform(delete("/api/incidents/{id}/timeline/{eventId}", id, eventId).with(asAdminA()))
                .andReturn().getResponse().getStatus();
        int putStatus = mockMvc.perform(put("/api/incidents/{id}/timeline/{eventId}", id, eventId).with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();

        assertThat(deleteStatus).isIn(404, 405);
        assertThat(putStatus).isIn(404, 405);
        mockMvc.perform(get("/api/incidents/{id}/timeline", id).with(asAdminA()))
                .andExpect(jsonPath("$", hasSize(1)));
    }
}
