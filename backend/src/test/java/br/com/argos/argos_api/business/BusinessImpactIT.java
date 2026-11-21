package br.com.argos.argos_api.business;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NEG-01..04 on PostgreSQL: KPI setup, incident impact and the executive summary. */
class BusinessImpactIT extends AbstractIntegrationTest {

    private static final String ORDERS = """
            {"name":"Pedidos","unit":"pedidos","baselinePerMinute":10,"valuePerUnit":200,
             "services":[{"service":"checkout","impactPercent":100},{"service":"payment","impactPercent":80}]}
            """;

    @Test
    void kpiSetupIsValidatedAndRestrictedToWriters() throws Exception {
        mockMvc.perform(post("/api/business/kpis").with(asViewerA()).contentType(MediaType.APPLICATION_JSON).content(ORDERS))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/business/kpis").with(asEditorA()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"unit\":\"x\",\"baselinePerMinute\":-1}"))
                .andExpect(status().isBadRequest());

        String id = createKpi();
        mockMvc.perform(post("/api/business/kpis").with(asEditorA()).contentType(MediaType.APPLICATION_JSON).content(ORDERS))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/business/kpis").with(asViewerA()))
                .andExpect(jsonPath("$[0].services.length()").value(2));
        mockMvc.perform(get("/api/business/kpis").with(asAdminB()))
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(put("/api/business/kpis/{id}", id).with(asEditorA()).contentType(MediaType.APPLICATION_JSON)
                        .content(ORDERS.replace("\"impactPercent\":80", "\"impactPercent\":50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[?(@.service=='payment')].impactPercent").value(50.0));
        mockMvc.perform(delete("/api/business/kpis/{id}", id).with(asAdminB())).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/business/kpis/{id}", id).with(asEditorA())).andExpect(status().isNoContent());
    }

    @Test
    void resolvedIncidentCostFollowsTheSeverityWhenThereIsNoTelemetry() throws Exception {
        createKpi();
        // SEV2 (50% degradation) on checkout (100% of orders) for 30 minutes: 10/min x 0.5 x 30 = 150 orders = R$ 30.000
        UUID incidentId = insertIncident("SEV2", "checkout", Duration.ofMinutes(30), true);

        mockMvc.perform(get("/api/incidents/{id}/impact", incidentId).with(asViewerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("SEVERIDADE"))
                .andExpect(jsonPath("$.minutes").value(30))
                .andExpect(jsonPath("$.ongoing").value(false))
                .andExpect(jsonPath("$.kpis[0].lostUnits").value(150.0))
                .andExpect(jsonPath("$.totalValue").value(30000.0));
        mockMvc.perform(get("/api/incidents/{id}/impact", incidentId).with(asAdminB())).andExpect(status().isNotFound());
    }

    @Test
    void incidentsWithoutLinkedServicesHaveNoEstimate() throws Exception {
        createKpi();
        UUID incidentId = insertIncident("SEV1", "search", Duration.ofMinutes(10), true);

        mockMvc.perform(get("/api/incidents/{id}/impact", incidentId).with(asViewerA()))
                .andExpect(jsonPath("$.method").value("SEM_KPI"))
                .andExpect(jsonPath("$.totalValue").value(0));
    }

    @Test
    void executiveSummaryAddsUpTheCostOfThePeriod() throws Exception {
        createKpi();
        insertIncident("SEV1", "checkout", Duration.ofMinutes(10), true);  // 10 x 1.0 x 10 = 100 orders = R$ 20.000
        insertIncident("SEV3", "payment", Duration.ofMinutes(20), true);   // 10 x 0.8 x 0.2 x 20 = 32 orders = R$ 6.400

        MvcResult result = mockMvc.perform(get("/api/business/executive?days=7").with(asExecutiveA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpisConfigured").value(true))
                .andExpect(jsonPath("$.incidents").value(2))
                .andExpect(jsonPath("$.criticalIncidents").value(1))
                .andExpect(jsonPath("$.topIncidents[0].severity").value("SEV1"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(body, "$.totalLoss")).doubleValue()).isEqualTo(26400.0);
        assertThat(((Number) JsonPath.read(body, "$.lossByKpi[0].lostUnits")).doubleValue()).isEqualTo(132.0);
        assertThat((Integer) JsonPath.read(body, "$.lossByDay.length()")).isGreaterThanOrEqualTo(7);
        mockMvc.perform(get("/api/business/executive?days=0").with(asExecutiveA())).andExpect(status().isBadRequest());
    }

    @Test
    void homeListsActiveIncidentsWithTheirEstimatedCost() throws Exception {
        createKpi();
        UUID open = insertIncident("SEV1", "checkout", Duration.ofMinutes(10), false);
        insertIncident("SEV2", "payment", Duration.ofMinutes(10), true); // resolved: not listed
        UUID unpriced = insertIncident("SEV3", "search", Duration.ofMinutes(10), false);

        mockMvc.perform(get("/api/business/active-incidents").with(asViewerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(open.toString()))
                .andExpect(jsonPath("$[0].impactMethod").value("SEVERIDADE"))
                .andExpect(jsonPath("$[1].id").value(unpriced.toString()))
                .andExpect(jsonPath("$[1].impactValue").doesNotExist());
        mockMvc.perform(get("/api/business/active-incidents").with(asAdminB()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    private String createKpi() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/business/kpis").with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON).content(ORDERS))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private UUID insertIncident(String severity, String service, Duration length, boolean resolved) {
        UUID id = UUID.randomUUID();
        Instant opened = Instant.now().minus(Duration.ofDays(1));
        jdbc.update("""
                INSERT INTO incidents (id, organization_id, title, severity, status, source, affected_services, opened_at,
                                       resolved_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ALERT', ARRAY[?], ?, ?, NOW())
                """, id, orgA, "Falha em " + service, severity, resolved ? "RESOLVED" : "OPEN", service,
                Timestamp.from(opened), resolved ? Timestamp.from(opened.plus(length)) : null);
        return id;
    }
}
