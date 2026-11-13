package br.com.argos.argos_api;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import br.com.argos.argos_api.telemetry.OtlpPayloads;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quadro 2 / Seção 3.1.3: Suíte completa de verificação do isolamento Multi-Tenant
 * tanto na camada de aplicação/API (busca direta por UUID/traceId) quanto no motor
 * de Row-Level Security (ENABLE + FORCE ROW LEVEL SECURITY) do PostgreSQL.
 */
class MultiTenantIsolationIT extends AbstractIntegrationTest {

    private static final List<String> TENANT_TABLES = List.of(
            "users",
            "incidents",
            "incident_events",
            "api_keys",
            "alert_sources",
            "alert_incident_links",
            "deploys",
            "telemetry_spans",
            "telemetry_logs",
            "telemetry_metrics",
            "telemetry_rules"
    );

    private static final String TRACE_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String rawKeyA;
    private String rawKeyB;
    private UUID keyBId;

    @BeforeEach
    void setUpApiKeys() throws Exception {
        String respA = mockMvc.perform(post("/api/api-keys").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Key Org A\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        rawKeyA = JsonPath.read(respA, "$.key");

        String respB = mockMvc.perform(post("/api/api-keys").with(asAdminB())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Key Org B\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        rawKeyB = JsonPath.read(respB, "$.key");
        keyBId = UUID.fromString(JsonPath.read(respB, "$.id"));
    }

    @Test
    void allTenantTablesHaveEnableAndForceRowLevelSecurityAndIsolationPolicyInPostgres() {
        for (String table : TENANT_TABLES) {
            Boolean rlsEnabled = jdbc.queryForObject(
                    "SELECT relrowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table);
            Boolean rlsForced = jdbc.queryForObject(
                    "SELECT relforcerowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table);
            Integer policyCount = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_policies WHERE tablename = ? AND policyname = 'tenant_isolation_policy'",
                    Integer.class, table);

            assertThat(rlsEnabled).as("ENABLE ROW LEVEL SECURITY on " + table).isTrue();
            assertThat(rlsForced).as("FORCE ROW LEVEL SECURITY on " + table).isTrue();
            assertThat(policyCount).as("tenant_isolation_policy on " + table).isEqualTo(1);
        }
    }

    @Test
    void postgresqlRowLevelSecurityFiltersSelectsAndBlocksCrossTenantWritesAtDatabaseLevel() throws Exception {
        // Seed records in both Org A and Org B across all tenant tables
        String incRespA = mockMvc.perform(post("/api/incidents").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Incidente A\",\"severity\":\"SEV2\",\"affectedServices\":[\"svc-a\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID incA = UUID.fromString(JsonPath.read(incRespA, "$.id"));

        String incRespB = mockMvc.perform(post("/api/incidents").with(asAdminB())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Incidente B\",\"severity\":\"SEV1\",\"affectedServices\":[\"svc-b\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID incB = UUID.fromString(JsonPath.read(incRespB, "$.id"));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mockMvc.perform(post("/api/webhooks/deploys").header("X-API-Key", rawKeyA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"service\":\"svc-a\",\"version\":\"1.0.0\"}")).andExpect(status().isCreated());
        mockMvc.perform(post("/api/webhooks/deploys").header("X-API-Key", rawKeyB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"service\":\"svc-b\",\"version\":\"2.0.0\"}")).andExpect(status().isCreated());

        mockMvc.perform(post("/api/webhooks/otlp/v1/traces").header("X-API-Key", rawKeyA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OtlpPayloads.traces("svc-a",
                        OtlpPayloads.span(OtlpPayloads.TRACE_ID, "0000000000000001", null, "GET /a", now, 20, false))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/webhooks/otlp/v1/traces").header("X-API-Key", rawKeyB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OtlpPayloads.traces("svc-b",
                        OtlpPayloads.span(TRACE_B, "0000000000000002", null, "GET /b", now, 20, false))))
                .andExpect(status().isOk());

        // Execute raw SQL without WHERE clauses under non-superuser role argos_tenant with tenant = orgA
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE argos_tenant");
            jdbc.queryForObject("SELECT set_config('app.current_tenant', ?, true)", String.class, orgA.toString());

            for (String table : List.of("users", "incidents", "incident_events", "api_keys", "deploys", "telemetry_spans")) {
                List<UUID> visibleOrgs = jdbc.queryForList(
                        "SELECT DISTINCT organization_id FROM " + table, UUID.class);
                assertThat(visibleOrgs)
                        .as("RLS must restrict SELECT on " + table + " to orgA only")
                        .containsExactly(orgA);
            }

            // Cross-tenant UPDATE and DELETE by direct UUID affect 0 rows under RLS
            int updated = jdbc.update("UPDATE incidents SET title = 'invasao' WHERE id = ?", incB);
            int deleted = jdbc.update("DELETE FROM incidents WHERE id = ?", incB);
            assertThat(updated).isZero();
            assertThat(deleted).isZero();
            assertThat(jdbc.queryForObject("SELECT id FROM incidents WHERE id = ?", UUID.class, incA)).isEqualTo(incA);

            // Cross-tenant INSERT with organization_id = orgB is rejected by WITH CHECK policy
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO incidents (id, organization_id, title, severity, status, source, opened_at, updated_at)
                    VALUES (?, ?, 'Forjado', 'SEV1', 'OPEN', 'ALERT', now(), now())
                    """, UUID.randomUUID(), orgB))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("row-level security policy");
            status.setRollbackOnly();
        });
    }

    @Test
    void organizationACannotReadOrMutateOrganizationBIncidentsTimelineOrCommentsByDirectUuid() throws Exception {
        String createdB = mockMvc.perform(post("/api/incidents").with(asAdminB())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Segredo Org B\",\"severity\":\"SEV1\",\"affectedServices\":[\"core-b\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String incidentBId = JsonPath.read(createdB, "$.id");

        mockMvc.perform(post("/api/incidents/{id}/comments", incidentBId).with(asAdminB())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Comentario interno B\"}"))
                .andExpect(status().isCreated());

        // Organization A cannot read, update, comment, assign or view timeline/alerts of incidentBId
        mockMvc.perform(get("/api/incidents/{id}", incidentBId).with(asAdminA()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/incidents/{id}", incidentBId).with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/incidents/{id}/comments", incidentBId).with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Tentativa invasao\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/incidents/{id}/assignee", incidentBId).with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":\"" + adminA + "\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/incidents/{id}/timeline", incidentBId).with(asAdminA()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/incidents/{id}/alerts", incidentBId).with(asAdminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void organizationACannotReadOrMutateOrganizationBApiKeysAlertSourcesDeploysOrTelemetryByDirectId() throws Exception {
        // Org B creates an alert source
        String sourceBResp = mockMvc.perform(post("/api/alert-sources").with(asAdminB())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Grafana B","titlePath":"$.title","dedupKeyPath":"$.key","defaultSeverity":"SEV2"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sourceBId = JsonPath.read(sourceBResp, "$.id");

        // Org B creates a telemetry rule
        String ruleBResp = mockMvc.perform(post("/api/telemetry/rules").with(asAdminB())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service\":\"checkout-b\",\"maxErrorRatePercent\":2.0,\"maxP95LatencyMs\":600.0}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String ruleBId = JsonPath.read(ruleBResp, "$.id");

        // Org B sends a deploy and a trace
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mockMvc.perform(post("/api/webhooks/deploys").header("X-API-Key", rawKeyB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"service\":\"checkout-b\",\"version\":\"9.9.9\"}")).andExpect(status().isCreated());
        mockMvc.perform(post("/api/webhooks/otlp/v1/traces").header("X-API-Key", rawKeyB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OtlpPayloads.traces("checkout-b",
                        OtlpPayloads.span(TRACE_B, "0000000000000099", null, "POST /pay", now, 50, true))))
                .andExpect(status().isOk());

        // Org A cannot revoke Org B's API key by direct UUID
        mockMvc.perform(post("/api/api-keys/{id}/revoke", keyBId).with(asAdminA()))
                .andExpect(status().isNotFound());

        // Org A cannot read, update, or delete Org B's alert source by direct UUID
        mockMvc.perform(get("/api/alert-sources/{id}", sourceBId).with(asAdminA()))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/alert-sources/{id}", sourceBId).with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hack\",\"titlePath\":\"$.t\",\"dedupKeyPath\":\"$.k\",\"defaultSeverity\":\"SEV1\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/alert-sources/{id}", sourceBId).with(asAdminA()))
                .andExpect(status().isNotFound());

        // Org A cannot see Org B's deploy or DORA metrics
        mockMvc.perform(get("/api/deploys").with(asAdminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
        mockMvc.perform(get("/api/metrics/dora").with(asAdminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deploymentFrequency.totalDeploys").value(0));

        // Org A cannot read Org B's trace by direct traceId, topology, RED series, or delete Org B's rule
        mockMvc.perform(get("/api/telemetry/traces/{traceId}", TRACE_B).with(asAdminA()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/telemetry/topology").with(asAdminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes", hasSize(0)));
        mockMvc.perform(get("/api/telemetry/red").with(asAdminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(0));
        mockMvc.perform(delete("/api/telemetry/rules/{id}", ruleBId).with(asAdminA()))
                .andExpect(status().isNoContent());

        // Verify Org B's rule is still present for Org B
        mockMvc.perform(get("/api/telemetry/rules").with(asAdminB()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(ruleBId));
    }
}
