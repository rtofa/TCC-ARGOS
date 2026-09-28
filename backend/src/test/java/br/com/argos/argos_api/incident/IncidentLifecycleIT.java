package br.com.argos.argos_api.incident;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IncidentLifecycleIT extends AbstractIncidentIT {

    private static final String NEW_INCIDENT = """
            {"title":"Checkout fora do ar","description":"Erro 500","severity":"SEV1",
             "affectedServices":["checkout-api"]}
            """;

    private String createIncident(RequestPostProcessor user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/incidents").with(user)
                        .contentType(MediaType.APPLICATION_JSON).content(NEW_INCIDENT))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private RequestPostProcessor patchBody(String json) {
        return request -> {
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent(json.getBytes());
            return request;
        };
    }

    @Test
    void registersIncidentAsOpen() throws Exception {
        mockMvc.perform(post("/api/incidents").with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON).content(NEW_INCIDENT))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/incidents/")))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.severity").value("SEV1"))
                .andExpect(jsonPath("$.affectedServices[0]").value("checkout-api"))
                .andExpect(jsonPath("$.createdBy").value(editorA.toString()))
                .andExpect(jsonPath("$.openedAt", notNullValue()))
                .andExpect(jsonPath("$.mttaSeconds", nullValue()));
    }

    @Test
    void walksStatusesUntilResolved() throws Exception {
        String id = createIncident(asEditorA());

        mockMvc.perform(get("/api/incidents/{id}", id).with(asViewerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Checkout fora do ar"));

        mockMvc.perform(patch("/api/incidents/{id}", id).with(asEditorA()).with(patchBody("{\"status\":\"INVESTIGATING\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.acknowledgedAt", notNullValue()))
                .andExpect(jsonPath("$.mttaSeconds", notNullValue()));

        mockMvc.perform(patch("/api/incidents/{id}", id).with(asAdminA()).with(patchBody("{\"status\":\"RESOLVED\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt", notNullValue()))
                .andExpect(jsonPath("$.mttrSeconds", notNullValue()));
    }

    @Test
    void editsFieldsAndSeverity() throws Exception {
        String id = createIncident(asEditorA());

        mockMvc.perform(patch("/api/incidents/{id}", id).with(asEditorA()).with(patchBody("""
                        {"title":"Pagamentos lentos","severity":"SEV3","affectedServices":["payment-api"]}
                        """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Pagamentos lentos"))
                .andExpect(jsonPath("$.severity").value("SEV3"))
                .andExpect(jsonPath("$.affectedServices[0]").value("payment-api"));
    }

    @Test
    void assignsOnlyMembersOfTheOrganization() throws Exception {
        String id = createIncident(asEditorA());

        mockMvc.perform(put("/api/incidents/{id}/assignee", id).with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":\"" + viewerA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(viewerA.toString()))
                .andExpect(jsonPath("$.acknowledgedAt", notNullValue()));

        mockMvc.perform(put("/api/incidents/{id}/assignee", id).with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":\"" + adminB + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/incidents/{id}/assignee", id).with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId", nullValue()));
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        mockMvc.perform(post("/api/incidents").with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\",\"severity\":\"SEV1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail", startsWith("title:")));

        mockMvc.perform(post("/api/incidents").with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\",\"severity\":\"SEV9\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mockMvc.perform(post("/api/incidents").with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isBadRequest());

        String id = createIncident(asEditorA());
        mockMvc.perform(patch("/api/incidents/{id}", id).with(asEditorA()).with(patchBody("{}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void viewerAndExecutiveCannotWrite() throws Exception {
        String id = createIncident(asEditorA());

        for (RequestPostProcessor readOnly : new RequestPostProcessor[]{asViewerA(), asExecutiveA()}) {
            mockMvc.perform(post("/api/incidents").with(readOnly)
                            .contentType(MediaType.APPLICATION_JSON).content(NEW_INCIDENT))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/incidents/{id}", id).with(readOnly).with(patchBody("{\"status\":\"RESOLVED\"}")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/incidents/{id}/assignee", id).with(readOnly)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":null}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/incidents/{id}", id).with(readOnly))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void otherOrganizationSeesNotFound() throws Exception {
        String id = createIncident(asEditorA());

        mockMvc.perform(get("/api/incidents/{id}", id).with(asAdminB()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/incidents/{id}", id).with(asAdminB()).with(patchBody("{\"status\":\"RESOLVED\"}")))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/incidents/{id}/assignee", id).with(asAdminB())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":null}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/incidents/{id}", id).with(asAdminA()))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/incidents/{id}", java.util.UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
