package br.com.argos.argos_api.apikey;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiKeyIT extends AbstractIntegrationTest {

    private String createKey(RequestPostProcessor admin, String name) throws Exception {
        return mockMvc.perform(post("/api/api-keys").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void createsKeyShowingTheSecretOnlyOnce() throws Exception {
        String created = createKey(asAdminA(), "Grafana produção");
        String key = JsonPath.read(created, "$.key");
        String prefix = JsonPath.read(created, "$.prefix");

        assertThat(key).startsWith("argos_");
        assertThat(prefix).isEqualTo(key.substring(0, 12));

        mockMvc.perform(get("/api/api-keys").with(asAdminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Grafana produção"))
                .andExpect(jsonPath("$[0].prefix").value(prefix))
                .andExpect(jsonPath("$[0].createdBy").value(adminA.toString()))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].lastUsedAt", nullValue()))
                .andExpect(jsonPath("$[0].key").doesNotExist());

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM api_keys");
        assertThat(rows).singleElement().satisfies(row ->
                assertThat(row.values()).noneMatch(value -> value != null && value.toString().contains(key)));
    }

    @Test
    void keyAuthenticatesWebhookRoutesWithEitherHeader() throws Exception {
        String key = JsonPath.read(createKey(asAdminA(), "Ping"), "$.key");

        mockMvc.perform(get("/api/webhooks/ping").header("X-API-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(orgA.toString()))
                .andExpect(jsonPath("$.keyPrefix").value(key.substring(0, 12)));
        mockMvc.perform(get("/api/webhooks/ping").header("Authorization", "Bearer " + key))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/api-keys").with(asAdminA()))
                .andExpect(jsonPath("$[0].lastUsedAt", notNullValue()));
    }

    @Test
    void revokedKeyStopsWorking() throws Exception {
        String created = createKey(asAdminA(), "Vazou");
        String id = JsonPath.read(created, "$.id");
        String key = JsonPath.read(created, "$.key");

        mockMvc.perform(post("/api/api-keys/{id}/revoke", id).with(asAdminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAt", notNullValue()));

        mockMvc.perform(get("/api/webhooks/ping").header("X-API-Key", key))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/api-keys").with(asAdminA()))
                .andExpect(jsonPath("$[0].status").value("REVOKED"));
    }

    @Test
    void webhookRoutesRejectMissingInvalidKeysAndJwt() throws Exception {
        mockMvc.perform(get("/api/webhooks/ping"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/webhooks/ping").header("X-API-Key", "argos_inventada"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/webhooks/ping").with(asAdminA()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void keyIsNotAcceptedOutsideWebhooks() throws Exception {
        String key = JsonPath.read(createKey(asAdminA(), "Restrita"), "$.key");

        mockMvc.perform(get("/api/incidents").header("X-API-Key", key))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/incidents").header("Authorization", "Bearer " + key))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsManageKeys() throws Exception {
        String id = JsonPath.read(createKey(asAdminA(), "Admin"), "$.id");

        for (RequestPostProcessor user : new RequestPostProcessor[]{asEditorA(), asViewerA(), asExecutiveA()}) {
            mockMvc.perform(post("/api/api-keys").with(user)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/api-keys").with(user))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/api-keys/{id}/revoke", id).with(user))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void otherOrganizationCannotSeeOrRevoke() throws Exception {
        String id = JsonPath.read(createKey(asAdminA(), "Da A"), "$.id");

        mockMvc.perform(get("/api/api-keys").with(asAdminB()))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(post("/api/api-keys/{id}/revoke", id).with(asAdminB()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/api-keys").with(asAdminA()))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void validatesName() throws Exception {
        mockMvc.perform(post("/api/api-keys").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/api-keys").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
