package br.com.argos.argos_api.alerting;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertSourceIT extends AbstractIntegrationTest {

    private static final String GRAFANA_SOURCE = """
            {"name":"Grafana","itemsPath":"$.alerts","titlePath":"$.annotations.summary",
             "dedupKeyPath":"$.fingerprint","statusPath":"$.status","severityPath":"$.labels.severity",
             "servicePath":"$.labels.service","descriptionPath":"$.annotations.description",
             "statusMap":{"firing":"firing","resolved":"resolved"},
             "severityMap":{"critical":"SEV1","warning":"SEV3"},"defaultSeverity":"SEV3"}
            """;

    private String keyA;
    private String grafanaPayload;

    @BeforeEach
    void setUp() throws Exception {
        keyA = JsonPath.read(mockMvc.perform(post("/api/api-keys").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Grafana\"}"))
                .andReturn().getResponse().getContentAsString(), "$.key");
        grafanaPayload = new String(getClass().getResourceAsStream("/alerting/grafana-webhook.json").readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private String createSource(RequestPostProcessor user) throws Exception {
        return mockMvc.perform(post("/api/alert-sources").with(user)
                        .contentType(MediaType.APPLICATION_JSON).content(GRAFANA_SOURCE))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions sendTo(String webhookPath, String key, String body) throws Exception {
        return mockMvc.perform(post(webhookPath).header("X-API-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void crudWithWebhookPath() throws Exception {
        String created = createSource(asEditorA());
        String id = JsonPath.read(created, "$.id");
        assertThat((String) JsonPath.read(created, "$.webhookPath")).isEqualTo("/api/webhooks/alerts/" + id);

        mockMvc.perform(get("/api/alert-sources").with(asViewerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].severityMap.critical").value("SEV1"));
        mockMvc.perform(put("/api/alert-sources/{id}", id).with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content(GRAFANA_SOURCE.replace("\"Grafana\"", "\"Grafana 2\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Grafana 2"));
        mockMvc.perform(get("/api/alert-sources/{id}", id).with(asExecutiveA()))
                .andExpect(jsonPath("$.name").value("Grafana 2"));
        mockMvc.perform(delete("/api/alert-sources/{id}", id).with(asEditorA()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/alert-sources/{id}", id).with(asAdminA()))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAdminsAndEditorsWrite() throws Exception {
        String id = JsonPath.read(createSource(asAdminA()), "$.id");

        for (RequestPostProcessor readOnly : new RequestPostProcessor[]{asViewerA(), asExecutiveA()}) {
            mockMvc.perform(post("/api/alert-sources").with(readOnly)
                            .contentType(MediaType.APPLICATION_JSON).content(GRAFANA_SOURCE))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/alert-sources/{id}", id).with(readOnly)
                            .contentType(MediaType.APPLICATION_JSON).content(GRAFANA_SOURCE))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/alert-sources/{id}", id).with(readOnly))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void otherOrganizationSeesNotFound() throws Exception {
        String id = JsonPath.read(createSource(asAdminA()), "$.id");

        mockMvc.perform(get("/api/alert-sources/{id}", id).with(asAdminB())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/alert-sources").with(asAdminB())).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(delete("/api/alert-sources/{id}", id).with(asAdminB())).andExpect(status().isNotFound());

        String keyB = JsonPath.read(mockMvc.perform(post("/api/api-keys").with(asAdminB())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"B\"}"))
                .andReturn().getResponse().getContentAsString(), "$.key");
        sendTo("/api/webhooks/alerts/" + id, keyB, grafanaPayload).andExpect(status().isNotFound());
    }

    @Test
    void validatesRules() throws Exception {
        mockMvc.perform(post("/api/alert-sources").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GRAFANA_SOURCE.replace("$.annotations.summary", "$[?(")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/alert-sources").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GRAFANA_SOURCE.replace("\"dedupKeyPath\":\"$.fingerprint\",", "")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/alert-sources").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GRAFANA_SOURCE.replace("\"critical\":\"SEV1\"", "\"critical\":\"URGENTE\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void processesRealGrafanaPayload() throws Exception {
        String sourceId = JsonPath.read(createSource(asAdminA()), "$.id");
        String webhookPath = "/api/webhooks/alerts/" + sourceId;

        String response = sendTo(webhookPath, keyA, grafanaPayload)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.results[0].action").value("OPENED"))
                .andExpect(jsonPath("$.results[0].dedupKey").value("c6eadffa33fcdf37"))
                .andExpect(jsonPath("$.results[1].action").value("IGNORED"))
                .andReturn().getResponse().getContentAsString();
        String incidentId = JsonPath.read(response, "$.results[0].incidentId");

        mockMvc.perform(get("/api/incidents/{id}", incidentId).with(asAdminA()))
                .andExpect(jsonPath("$.title").value("CPU acima de 90% no checkout-api"))
                .andExpect(jsonPath("$.severity").value("SEV1"))
                .andExpect(jsonPath("$.affectedServices[0]").value("checkout-api"))
                .andExpect(jsonPath("$.source").value("ALERT"));
        mockMvc.perform(get("/api/incidents/{id}/alerts", incidentId).with(asAdminA()))
                .andExpect(jsonPath("$[0].sourceName").value("Grafana"))
                .andExpect(jsonPath("$[0].sourceId").value(sourceId));

        String allResolved = grafanaPayload.replace("\"status\": \"firing\"", "\"status\": \"resolved\"");
        sendTo(webhookPath, keyA, allResolved)
                .andExpect(jsonPath("$.results[0].action").value("RESOLVED"))
                .andExpect(jsonPath("$.results[0].incidentId").value(incidentId));
        mockMvc.perform(get("/api/incidents/{id}", incidentId).with(asAdminA()))
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    void sameDedupKeyInDifferentSourcesAreDifferentIncidents() throws Exception {
        String sourceId = JsonPath.read(createSource(asAdminA()), "$.id");

        String viaSource = JsonPath.read(sendTo("/api/webhooks/alerts/" + sourceId, keyA, grafanaPayload)
                .andReturn().getResponse().getContentAsString(), "$.results[0].incidentId");
        String viaDefault = JsonPath.read(sendTo("/api/webhooks/alerts", keyA,
                        "{\"title\":\"CPU\",\"dedupKey\":\"c6eadffa33fcdf37\"}")
                .andExpect(jsonPath("$.results[0].action").value("OPENED"))
                .andReturn().getResponse().getContentAsString(), "$.results[0].incidentId");

        assertThat(viaDefault).isNotEqualTo(viaSource);
    }

    @Test
    void deletedSourceStopsReceivingButKeepsIncidents() throws Exception {
        String sourceId = JsonPath.read(createSource(asAdminA()), "$.id");
        String incidentId = JsonPath.read(sendTo("/api/webhooks/alerts/" + sourceId, keyA, grafanaPayload)
                .andReturn().getResponse().getContentAsString(), "$.results[0].incidentId");

        mockMvc.perform(delete("/api/alert-sources/{id}", sourceId).with(asAdminA()))
                .andExpect(status().isNoContent());

        sendTo("/api/webhooks/alerts/" + sourceId, keyA, grafanaPayload).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/incidents/{id}", incidentId).with(asAdminA()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/incidents/{id}/alerts", incidentId).with(asAdminA()))
                .andExpect(jsonPath("$[0].sourceName").value("Grafana"));
    }
}
