package br.com.argos.argos_api.delivery;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeployIT extends AbstractIntegrationTest {

    private String keyA;
    private String keyB;

    @BeforeEach
    void createKeys() throws Exception {
        keyA = createKey(asAdminA());
        keyB = createKey(asAdminB());
    }

    private String createKey(RequestPostProcessor admin) throws Exception {
        String created = mockMvc.perform(post("/api/api-keys").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"GitHub Actions\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.key");
    }

    private ResultActions report(String key, String body) throws Exception {
        return mockMvc.perform(post("/api/webhooks/deploys").header("X-API-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String deploy(String service, String version, String environment, Instant deployedAt) {
        return """
                {"service":"%s","version":"%s","environment":"%s","commitSha":"a1b2c3d4e5f6",
                 "author":"ana","url":"https://github.com/acme/shop/actions/runs/42","deployedAt":"%s"}
                """.formatted(service, version, environment, deployedAt);
    }

    @Test
    void pipelineReportsDeployAndEveryRoleCanList() throws Exception {
        Instant deployedAt = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

        report(keyA, deploy("checkout-api", "2.3.0", "production", deployedAt))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.service").value("checkout-api"))
                .andExpect(jsonPath("$.version").value("2.3.0"))
                .andExpect(jsonPath("$.commitSha").value("a1b2c3d4e5f6"))
                .andExpect(jsonPath("$.deployedAt").value(deployedAt.toString()));

        for (RequestPostProcessor user : new RequestPostProcessor[]{asAdminA(), asEditorA(), asViewerA(),
                asExecutiveA()}) {
            mockMvc.perform(get("/api/deploys").with(user))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].environment").value("production"))
                    .andExpect(jsonPath("$.content[0].url").value("https://github.com/acme/shop/actions/runs/42"));
        }
    }

    @Test
    void organizationsOnlySeeTheirOwnDeploys() throws Exception {
        report(keyA, "{\"service\":\"checkout-api\",\"version\":\"1\"}").andExpect(status().isCreated());
        report(keyB, "{\"service\":\"billing\",\"version\":\"9\"}").andExpect(status().isCreated());

        mockMvc.perform(get("/api/deploys").with(asAdminA()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].service").value("checkout-api"));
        mockMvc.perform(get("/api/deploys").with(asAdminB()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].service").value("billing"));
    }

    @Test
    void filtersByServiceEnvironmentAndPeriodNewestFirst() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        report(keyA, deploy("checkout-api", "1.0", "production", now.minus(3, ChronoUnit.HOURS)));
        report(keyA, deploy("checkout-api", "1.1", "production", now.minus(1, ChronoUnit.HOURS)));
        report(keyA, deploy("checkout-api", "1.2-rc", "staging", now.minus(30, ChronoUnit.MINUTES)));
        report(keyA, deploy("catalog-api", "5.0", "production", now.minus(20, ChronoUnit.MINUTES)));

        mockMvc.perform(get("/api/deploys").with(asViewerA())
                        .param("service", "checkout-api").param("environment", "production"))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].version").value("1.1"))
                .andExpect(jsonPath("$.content[1].version").value("1.0"));
        mockMvc.perform(get("/api/deploys").with(asViewerA())
                        .param("service", "checkout-api", "catalog-api")
                        .param("from", now.minus(2, ChronoUnit.HOURS).toString()))
                .andExpect(jsonPath("$.content[*].version").value(org.hamcrest.Matchers.contains("5.0", "1.2-rc", "1.1")));
        mockMvc.perform(get("/api/deploys").with(asViewerA()).param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(4))
                .andExpect(jsonPath("$.content[0].version").value("1.2-rc"));
    }

    @Test
    void webhookOnlyAcceptsApiKeysAndListOnlyAcceptsUsers() throws Exception {
        String body = "{\"service\":\"checkout-api\",\"version\":\"1\"}";

        mockMvc.perform(post("/api/webhooks/deploys").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/webhooks/deploys").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/deploys").header("X-API-Key", keyA))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/deploys"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedKeyCannotReportDeploys() throws Exception {
        String created = mockMvc.perform(post("/api/api-keys").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Antiga\"}"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/api-keys/{id}/revoke", (String) JsonPath.read(created, "$.id")).with(asAdminA()));

        report(JsonPath.read(created, "$.key"), "{\"service\":\"checkout-api\",\"version\":\"1\"}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validatesTheReportedDeploy() throws Exception {
        report(keyA, "{\"version\":\"1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("service")));
        report(keyA, "{\"service\":\"x\",\"version\":\"1\",\"url\":\"javascript:alert(1)\"}")
                .andExpect(status().isBadRequest());
        report(keyA, "{\"service\":\"x\",\"version\":\"1\",\"commitSha\":\"not-a-sha\"}")
                .andExpect(status().isBadRequest());
        report(keyA, "{\"service\":\"x\",\"version\":\"1\",\"deployedAt\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}")
                .andExpect(status().isBadRequest());
        report(keyA, "{malformed").andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/deploys").with(asViewerA()).param("from", "ontem"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void doraMetricsCalculatesAllFourMetricsAndCorrelatesDeployWithIncident() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant deploy1At = now.minus(30, ChronoUnit.MINUTES);
        Instant commit1At = deploy1At.minus(20, ChronoUnit.MINUTES); // Lead time = 1200s (20m)
        Instant deploy2At = now.minus(10, ChronoUnit.MINUTES);
        Instant commit2At = deploy2At.minus(10, ChronoUnit.MINUTES); // Lead time = 600s (10m)

        // Deploy 1 (checkout-api v1.4.2) -> followed 4 mins later by an incident on checkout-api
        report(keyA, """
                {"service":"checkout-api","version":"v1.4.2","environment":"production",
                 "author":"ci-bot","committedAt":"%s","deployedAt":"%s"}
                """.formatted(commit1At, deploy1At))
                .andExpect(status().isCreated());

        // Deploy 2 (checkout-api v1.4.3) -> clean deploy
        report(keyA, """
                {"service":"checkout-api","version":"v1.4.3","environment":"production",
                 "author":"ci-bot","committedAt":"%s","deployedAt":"%s"}
                """.formatted(commit2At, deploy2At))
                .andExpect(status().isCreated());

        // Insert an incident opened 4 minutes after deploy 1, resolved 15 minutes later (MTTR = 900s)
        Instant incOpenedAt = deploy1At.plus(4, ChronoUnit.MINUTES);
        Instant incAckAt = incOpenedAt.plus(2, ChronoUnit.MINUTES);
        Instant incResolvedAt = incOpenedAt.plus(15, ChronoUnit.MINUTES);
        jdbc.update("""
                INSERT INTO incidents (id, organization_id, title, severity, status, source, affected_services,
                                       created_by, opened_at, acknowledged_at, resolved_at, updated_at)
                VALUES (?, ?, 'Falha após deploy', 'SEV1', 'RESOLVED', 'MANUAL', ARRAY['checkout-api'],
                        ?, ?, ?, ?, ?)
                """, java.util.UUID.randomUUID(), orgA, adminA,
                java.sql.Timestamp.from(incOpenedAt), java.sql.Timestamp.from(incAckAt),
                java.sql.Timestamp.from(incResolvedAt), java.sql.Timestamp.from(incResolvedAt));

        mockMvc.perform(get("/api/metrics/dora").with(asExecutiveA())
                        .param("service", "checkout-api")
                        .param("failureWindowMinutes", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deploymentFrequency.totalDeploys").value(2))
                .andExpect(jsonPath("$.leadTimeForChanges.averageSeconds").value(900))
                .andExpect(jsonPath("$.leadTimeForChanges.level").value("ELITE"))
                .andExpect(jsonPath("$.changeFailureRate.totalDeploys").value(2))
                .andExpect(jsonPath("$.changeFailureRate.failedDeploys").value(1))
                .andExpect(jsonPath("$.changeFailureRate.percentage").value(50.0))
                .andExpect(jsonPath("$.meanTimeToRestore.totalIncidents").value(1))
                .andExpect(jsonPath("$.meanTimeToRestore.mttrSeconds").value(900))
                .andExpect(jsonPath("$.meanTimeToRestore.level").value("ELITE"))
                .andExpect(jsonPath("$.correlatedIncidents", hasSize(1)))
                .andExpect(jsonPath("$.correlatedIncidents[0].version").value("v1.4.2"))
                .andExpect(jsonPath("$.correlatedIncidents[0].author").value("ci-bot"))
                .andExpect(jsonPath("$.correlatedIncidents[0].minutesAfterDeploy").value(4));
    }

    @Test
    void qualityGateApprovesCleanServiceAndBlocksWhenSev1OrCriticalAlertExists() throws Exception {
        // 1. Clean state -> PASSED (200 OK even with strict=true)
        mockMvc.perform(get("/api/webhooks/deploys/quality-gate")
                        .header("X-API-Key", keyA)
                        .param("service", "checkout-api")
                        .param("strict", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.openSev1Incidents").value(0));

        // 2. Open SEV1 incident on checkout-api -> BLOCKED (409 Conflict with strict=true, 200 OK on UI endpoint)
        java.util.UUID sev1Id = java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incidents (id, organization_id, title, severity, status, source, affected_services,
                                       created_by, opened_at, updated_at)
                VALUES (?, ?, 'Queda crítica no checkout', 'SEV1', 'OPEN', 'MANUAL', ARRAY['checkout-api'],
                        ?, now(), now())
                """, sev1Id, orgA, adminA);

        mockMvc.perform(get("/api/webhooks/deploys/quality-gate")
                        .header("X-API-Key", keyA)
                        .param("service", "checkout-api")
                        .param("strict", "true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.openSev1Incidents").value(1))
                .andExpect(jsonPath("$.blockingIncidents", hasSize(1)))
                .andExpect(jsonPath("$.blockingIncidents[0].title").value("Queda crítica no checkout"));

        // Unrelated service (catalog-api) is still PASSED
        mockMvc.perform(get("/api/webhooks/deploys/quality-gate")
                        .header("X-API-Key", keyA)
                        .param("service", "catalog-api")
                        .param("strict", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.status").value("PASSED"));

        // UI endpoint (/api/deploys/quality-gate) returns status for logged-in user
        mockMvc.perform(get("/api/deploys/quality-gate").with(asViewerA())
                        .param("service", "checkout-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.status").value("BLOCKED"));

        // 3. Resolve SEV1 incident, open a critical ALERT monitor incident (SEV2 + source=ALERT) -> still BLOCKED
        jdbc.update("UPDATE incidents SET status = 'RESOLVED', resolved_at = now() WHERE id = ?", sev1Id);
        jdbc.update("""
                INSERT INTO incidents (id, organization_id, title, severity, status, source, affected_services,
                                       created_by, opened_at, updated_at)
                VALUES (?, ?, '[SLO OTLP] Latência crítica em checkout-api', 'SEV2', 'INVESTIGATING', 'ALERT',
                        ARRAY['checkout-api'], ?, now(), now())
                """, java.util.UUID.randomUUID(), orgA, adminA);

        mockMvc.perform(get("/api/webhooks/deploys/quality-gate")
                        .header("X-API-Key", keyA)
                        .param("service", "checkout-api")
                        .param("strict", "true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.openSev1Incidents").value(0))
                .andExpect(jsonPath("$.openCriticalAlerts").value(1));
    }
}
