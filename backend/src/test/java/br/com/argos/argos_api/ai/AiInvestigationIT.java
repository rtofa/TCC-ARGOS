package br.com.argos.argos_api.ai;

import br.com.argos.argos_api.ai.investigation.Investigation;
import br.com.argos.argos_api.ai.investigation.InvestigationRepository;
import br.com.argos.argos_api.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI investigations end to end on PostgreSQL: queue, worker, persistence, tenancy and the assistant (heuristic mode). */
class AiInvestigationIT extends AbstractIntegrationTest {

    private static final String NEW_INCIDENT = """
            {"title":"Checkout fora do ar","description":"Erro 500 para maria@loja.com","severity":"SEV1",
             "affectedServices":["checkout-api"]}
            """;

    @Autowired
    InvestigationRepository repository;

    @Test
    void manualInvestigationRunsInTheBackgroundAndStoresTheReport() throws Exception {
        String incidentId = createIncident(asEditorA());

        mockMvc.perform(get("/api/incidents/{id}/investigation", incidentId).with(asViewerA()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/incidents/{id}/investigation", incidentId).with(asEditorA()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.trigger").value("MANUAL"));

        String body = awaitJson("/api/incidents/" + incidentId + "/investigation", asViewerA(),
                json -> "COMPLETED".equals(JsonPath.read(json, "$.status")));

        assertThat((String) JsonPath.read(body, "$.mode")).isEqualTo("HEURISTIC");
        assertThat((String) JsonPath.read(body, "$.report.rootCause.summary")).isNotBlank();
        assertThat((String) JsonPath.read(body, "$.report.executiveSummary")).contains("Checkout fora do ar");
        assertThat(body).doesNotContain("maria@loja.com").doesNotContain("organizationId");
        mockMvc.perform(get("/api/incidents/{id}/investigations", incidentId).with(asViewerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void anIncidentHasAtMostOneActiveInvestigation() throws Exception {
        UUID incidentId = UUID.fromString(createIncident(asEditorA()));
        Instant later = Instant.now().plus(Duration.ofHours(1)); // keeps the poller away

        Investigation first = repository.enqueue(orgA, incidentId, "AUTO", null, later);
        Investigation second = repository.enqueue(orgA, incidentId, "MANUAL", editorA, later);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_investigations WHERE incident_id = ?", Integer.class,
                incidentId)).isEqualTo(1);
    }

    @Test
    void investigationsAreIsolatedPerOrganizationAndOnlyWritersCanRequestThem() throws Exception {
        String incidentId = createIncident(asEditorA());

        mockMvc.perform(post("/api/incidents/{id}/investigation", incidentId).with(asViewerA()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/incidents/{id}/investigation", incidentId).with(asAdminB()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/incidents/{id}/investigation", incidentId).with(asAdminB()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/incidents/{id}/assistant/messages", incidentId).with(asAdminB()))
                .andExpect(status().isNotFound());
    }

    @Test
    void homeListsActiveIncidentsWithTheirLatestInvestigation() throws Exception {
        String incidentId = createIncident(asEditorA());
        repository.enqueue(orgA, UUID.fromString(incidentId), "AUTO", null, Instant.now().plus(Duration.ofHours(1)));

        mockMvc.perform(get("/api/ai/active-incidents").with(asViewerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(incidentId))
                .andExpect(jsonPath("$[0].investigation.status").value("PENDING"))
                .andExpect(jsonPath("$[0].impactValue").doesNotExist());
        mockMvc.perform(get("/api/ai/active-incidents").with(asAdminB()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void statusReportsHeuristicModeWithoutAnApiKey() throws Exception {
        mockMvc.perform(get("/api/ai/status").with(asViewerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.llmConfigured").value(false))
                .andExpect(jsonPath("$.mode").value("HEURISTIC"))
                .andExpect(jsonPath("$.routes.length()").value(0))
                .andExpect(jsonPath("$.configuredModels[0]").value("openai/gpt-oss-120b"));
    }

    @Test
    void assistantAnswersAsynchronouslyAndOneQuestionAtATime() throws Exception {
        String incidentId = createIncident(asEditorA());
        String question = "{\"question\":\"Qual a causa provável?\"}";

        mockMvc.perform(post("/api/incidents/{id}/assistant/messages", incidentId).with(asViewerA())
                        .contentType(MediaType.APPLICATION_JSON).content(question))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"));

        String conversation = awaitJson("/api/incidents/" + incidentId + "/assistant/messages", asViewerA(),
                json -> !"PENDING".equals(JsonPath.read(json, "$[1].status")));

        assertThat((String) JsonPath.read(conversation, "$[1].status")).isEqualTo("COMPLETED");
        assertThat((String) JsonPath.read(conversation, "$[1].content")).contains("não está configurada");
        mockMvc.perform(post("/api/incidents/{id}/assistant/messages", incidentId).with(asViewerA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private String createIncident(RequestPostProcessor user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/incidents").with(user)
                        .contentType(MediaType.APPLICATION_JSON).content(NEW_INCIDENT))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String awaitJson(String path, RequestPostProcessor user, Predicate<String> done) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        String body = "";
        while (Instant.now().isBefore(deadline)) {
            MvcResult result = mockMvc.perform(get(path).with(user)).andReturn();
            body = result.getResponse().getContentAsString();
            if (result.getResponse().getStatus() == 200 && !body.isBlank() && done.test(body)) {
                return body;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Tempo esgotado esperando " + path + "; última resposta: " + body);
    }

    @SuppressWarnings("unused")
    private static List<String> ignored() {
        return List.of();
    }
}
