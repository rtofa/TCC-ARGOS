package br.com.argos.argos_api.alerting;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertWebhookIT extends AbstractIntegrationTest {

    private static final String CPU_FIRING = """
            {"title":"CPU alta no checkout-api","dedupKey":"cpu-checkout","status":"firing",
             "severity":"critical","service":"checkout-api"}
            """;
    private static final String CPU_RESOLVED = """
            {"title":"CPU alta no checkout-api","dedupKey":"cpu-checkout","status":"resolved"}
            """;

    private String keyA;
    private String keyAId;
    private String keyB;

    @BeforeEach
    void createKeys() throws Exception {
        String created = createKey(asAdminA());
        keyA = JsonPath.read(created, "$.key");
        keyAId = JsonPath.read(created, "$.id");
        keyB = JsonPath.read(createKey(asAdminB()), "$.key");
    }

    private String createKey(RequestPostProcessor admin) throws Exception {
        return mockMvc.perform(post("/api/api-keys").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Monitoramento\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions send(String key, String body) throws Exception {
        return mockMvc.perform(post("/api/webhooks/alerts").header("X-API-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String sendAndGetIncident(String key, String body, String expectedAction) throws Exception {
        String response = send(key, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].action").value(expectedAction))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.results[0].incidentId");
    }

    private int incidentCount(java.util.UUID org) {
        return jdbc.queryForObject("SELECT count(*) FROM incidents WHERE organization_id = ?", Integer.class, org);
    }

    @Test
    void fullLifecycleOpensUpdatesResolvesAndReopens() throws Exception {
        String incidentId = sendAndGetIncident(keyA, CPU_FIRING, "OPENED");
        for (int i = 0; i < 3; i++) {
            assertThat(sendAndGetIncident(keyA, CPU_FIRING, "UPDATED")).isEqualTo(incidentId);
        }

        mockMvc.perform(get("/api/incidents/{id}", incidentId).with(asViewerA()))
                .andExpect(jsonPath("$.source").value("ALERT"))
                .andExpect(jsonPath("$.severity").value("SEV1"))
                .andExpect(jsonPath("$.affectedServices[0]").value("checkout-api"))
                .andExpect(jsonPath("$.createdBy", nullValue()))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(get("/api/incidents/{id}/alerts", incidentId).with(asViewerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dedupKey").value("cpu-checkout"))
                .andExpect(jsonPath("$[0].occurrences").value(4))
                .andExpect(jsonPath("$[0].sourceName").value("Formato padrão"))
                .andExpect(jsonPath("$[0].closedAt", nullValue()));

        assertThat(sendAndGetIncident(keyA, CPU_RESOLVED, "RESOLVED")).isEqualTo(incidentId);
        mockMvc.perform(get("/api/incidents/{id}", incidentId).with(asAdminA()))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.mttrSeconds", notNullValue()));
        mockMvc.perform(get("/api/incidents/{id}/timeline", incidentId).with(asAdminA()))
                .andExpect(jsonPath("$[*].type", contains("ALERT_TRIGGERED", "ALERT_RESOLVED")))
                .andExpect(jsonPath("$[0].actorType").value("SYSTEM"))
                .andExpect(jsonPath("$[0].actorId", nullValue()))
                .andExpect(jsonPath("$[0].apiKeyId").value(keyAId))
                .andExpect(jsonPath("$[0].newValue").value("cpu-checkout"))
                .andExpect(jsonPath("$[1].actorType").value("SYSTEM"));

        String secondIncident = sendAndGetIncident(keyA, CPU_FIRING, "OPENED");
        assertThat(secondIncident).isNotEqualTo(incidentId);
        assertThat(incidentCount(orgA)).isEqualTo(2);
    }

    @Test
    void severityChangeOnRepeatIsRecorded() throws Exception {
        String incidentId = sendAndGetIncident(keyA, CPU_FIRING, "OPENED");
        sendAndGetIncident(keyA, CPU_FIRING.replace("critical", "warning"), "UPDATED");

        mockMvc.perform(get("/api/incidents/{id}/timeline", incidentId).with(asAdminA()))
                .andExpect(jsonPath("$[*].type", contains("ALERT_TRIGGERED", "SEVERITY_CHANGED")))
                .andExpect(jsonPath("$[1].oldValue").value("SEV1"))
                .andExpect(jsonPath("$[1].newValue").value("SEV3"))
                .andExpect(jsonPath("$[1].actorType").value("SYSTEM"));
    }

    @Test
    void resolvedWithoutIncidentIsIgnored() throws Exception {
        send(keyA, CPU_RESOLVED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].action").value("IGNORED"));
        assertThat(incidentCount(orgA)).isZero();
    }

    @Test
    void incidentResolvedByAPersonIsRespected() throws Exception {
        String incidentId = sendAndGetIncident(keyA, CPU_FIRING, "OPENED");
        mockMvc.perform(patch("/api/incidents/{id}", incidentId).with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk());

        sendAndGetIncident(keyA, CPU_RESOLVED, "IGNORED");
        assertThat(sendAndGetIncident(keyA, CPU_FIRING, "OPENED")).isNotEqualTo(incidentId);
    }

    @Test
    void acceptsAndRejectsAlertsIndividually() throws Exception {
        send(keyA, """
                {"alerts":[{"title":"a","dedupKey":"a"},{"title":"sem chave"},{"title":"b","dedupKey":"b"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(3))
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.results[1].action").value("REJECTED"))
                .andExpect(jsonPath("$.results[1].reason").value("dedupKey ausente"));
        assertThat(incidentCount(orgA)).isEqualTo(2);
    }

    @Test
    void rejectsInvalidBodies() throws Exception {
        send(keyA, "").andExpect(status().isBadRequest());
        send(keyA, "{nao é json").andExpect(status().isBadRequest());

        StringBuilder tooMany = new StringBuilder("{\"alerts\":[");
        for (int i = 0; i < 101; i++) {
            tooMany.append(i == 0 ? "" : ",").append("{\"title\":\"t\",\"dedupKey\":\"").append(i).append("\"}");
        }
        send(keyA, tooMany.append("]}").toString()).andExpect(status().isBadRequest());

        String huge = "{\"title\":\"t\",\"dedupKey\":\"k\",\"description\":\"" + "x".repeat(1_048_576) + "\"}";
        send(keyA, huge).andExpect(status().is(413));
        assertThat(incidentCount(orgA)).isZero();
    }

    @Test
    void organizationsAreIsolated() throws Exception {
        String incidentA = sendAndGetIncident(keyA, CPU_FIRING, "OPENED");
        String incidentB = sendAndGetIncident(keyB, CPU_FIRING, "OPENED");

        assertThat(incidentA).isNotEqualTo(incidentB);
        assertThat(incidentCount(orgA)).isEqualTo(1);
        assertThat(incidentCount(orgB)).isEqualTo(1);
        mockMvc.perform(get("/api/incidents/{id}/alerts", incidentA).with(asAdminB()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void organizationInformedInTheBodyIsIgnored() throws Exception {
        send(keyA, "{\"title\":\"t\",\"dedupKey\":\"k\",\"organizationId\":\"" + orgB + "\"}")
                .andExpect(jsonPath("$.results[0].action").value("OPENED"));

        assertThat(incidentCount(orgA)).isEqualTo(1);
        assertThat(incidentCount(orgB)).isZero();
    }

    @Test
    void manualIncidentsAreNeverTouched() throws Exception {
        String manual = JsonPath.read(mockMvc.perform(post("/api/incidents").with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"CPU alta no checkout-api\",\"severity\":\"SEV2\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        sendAndGetIncident(keyA, CPU_FIRING, "OPENED");
        sendAndGetIncident(keyA, CPU_RESOLVED, "RESOLVED");

        mockMvc.perform(get("/api/incidents/{id}", manual).with(asAdminA()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.severity").value("SEV2"));
        mockMvc.perform(get("/api/incidents/{id}/alerts", manual).with(asAdminA()))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void concurrentDeliveriesCreateASingleIncident() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                futures.add(pool.submit(() -> send(keyA, CPU_FIRING).andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> future : futures) {
                assertThat(future.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(incidentCount(orgA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT occurrences FROM alert_incident_links WHERE organization_id = ?",
                Integer.class, orgA)).isEqualTo(100);
    }

    @Test
    void revokedKeyAndJwtAreRejected() throws Exception {
        mockMvc.perform(post("/api/api-keys/{id}/revoke", keyAId).with(asAdminA())).andExpect(status().isOk());

        send(keyA, CPU_FIRING).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/webhooks/alerts").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content(CPU_FIRING))
                .andExpect(status().isUnauthorized());
        assertThat(incidentCount(orgA)).isZero();
    }
}
