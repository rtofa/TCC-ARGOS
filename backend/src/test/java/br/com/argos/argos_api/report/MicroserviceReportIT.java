package br.com.argos.argos_api.report;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import br.com.argos.argos_api.telemetry.OtlpPayloads;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static br.com.argos.argos_api.telemetry.OtlpPayloads.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MicroserviceReportIT extends AbstractIntegrationTest {

    private static final String OK_TRACE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void seedMicroserviceData() throws Exception {
        String keyA = createKey(asAdminA());
        String keyB = createKey(asAdminB());

        ingest(keyA, "traces", OtlpPayloads.traces("frontend",
                OtlpPayloads.span(TRACE_ID, "0000000000000001", null, "GET /checkout", now.minusSeconds(120), 420, true)));
        ingest(keyA, "traces", OtlpPayloads.traces("cartservice",
                OtlpPayloads.span(TRACE_ID, "0000000000000002", "0000000000000001", "redis GET",
                        now.minusSeconds(119), 300, true)));
        ingest(keyA, "traces", OtlpPayloads.traces("frontend",
                OtlpPayloads.span(OK_TRACE, "0000000000000003", null, "GET /", now.minusSeconds(60), 18, false)));

        ingest(keyA, "logs", OtlpPayloads.logs("cartservice",
                OtlpPayloads.logRecord(now.minusSeconds(118), 17, "ERROR",
                        "Falha ao gravar usuario joao@empresa.com.br", TRACE_ID)));
        ingest(keyA, "metrics", OtlpPayloads.metrics("cartservice", now.minusSeconds(30), "http.server.duration", 125));

        mockMvc.perform(post("/api/incidents").with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Erro no cartservice","severity":"SEV2","affectedServices":["cartservice"]}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/webhooks/deploys").header("X-API-Key", keyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"service":"cartservice","version":"v2.1.0","environment":"production","author":"ci-bot"}
                                """))
                .andExpect(status().isCreated());

        // Org B telemetry must never appear in Org A's reports.
        ingest(keyB, "traces", OtlpPayloads.traces("segredo-b",
                OtlpPayloads.span("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "0000000000000009", null, "GET /b",
                        now.minusSeconds(30), 5, true)));
    }

    private String createKey(RequestPostProcessor admin) throws Exception {
        String created = mockMvc.perform(post("/api/api-keys").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"OTel\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.key");
    }

    private void ingest(String key, String signal, String body) throws Exception {
        mockMvc.perform(post("/api/webhooks/otlp/v1/" + signal).header("X-API-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    void previewConsolidatesTelemetryIncidentsDeploysAndMasksPii() throws Exception {
        mockMvc.perform(get("/api/reports/microservices")
                        .with(asExecutiveA())
                        .param("from", now.minus(1, ChronoUnit.HOURS).toString())
                        .param("to", now.plusSeconds(60).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalServices").value(2))
                .andExpect(jsonPath("$.summary.totalRequests").value(3))
                .andExpect(jsonPath("$.summary.totalErrors").value(2))
                .andExpect(jsonPath("$.summary.metricSeriesCount").value(3))
                .andExpect(jsonPath("$.summary.incidentsCount").value(1))
                .andExpect(jsonPath("$.summary.deploysCount").value(1))
                .andExpect(jsonPath("$.services", hasSize(2)))
                .andExpect(jsonPath("$.dependencies", hasSize(1)))
                .andExpect(jsonPath("$.metrics[0].name").value("http.server.duration"))
                .andExpect(jsonPath("$.errorLogs[0].body").value(containsString("[email]")));

        mockMvc.perform(get("/api/reports/microservices")
                        .with(asViewerA())
                        .param("service", "cartservice")
                        .param("from", now.minus(1, ChronoUnit.HOURS).toString())
                        .param("to", now.plusSeconds(60).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceFilter").value("cartservice"))
                .andExpect(jsonPath("$.services", hasSize(1)))
                .andExpect(jsonPath("$.services[0].service").value("cartservice"));
    }

    @Test
    void downloadExportsCsvJsonHtmlAndMarkdownWithAttachmentHeaders() throws Exception {
        String from = now.minus(1, ChronoUnit.HOURS).toString();
        String to = now.plusSeconds(60).toString();

        MvcResult csvResult = mockMvc.perform(get("/api/reports/microservices/download")
                        .with(asViewerA())
                        .param("format", "csv")
                        .param("from", from)
                        .param("to", to))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(".csv")))
                .andReturn();
        byte[] csvBytes = csvResult.getResponse().getContentAsByteArray();
        assertThat(csvBytes[0]).isEqualTo((byte) 0xEF);
        assertThat(csvBytes[1]).isEqualTo((byte) 0xBB);
        assertThat(csvBytes[2]).isEqualTo((byte) 0xBF);
        String csvText = new String(csvBytes, StandardCharsets.UTF_8);
        assertThat(csvText).contains("# RELATORIO DE MICROSSERVICOS - ARGOS")
                .contains("cartservice")
                .contains("frontend")
                .contains("http.server.duration")
                .doesNotContain("segredo-b");

        mockMvc.perform(get("/api/reports/microservices/download")
                        .with(asViewerA())
                        .param("format", "json")
                        .param("from", from)
                        .param("to", to))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(".json")))
                .andExpect(jsonPath("$.summary.totalServices").value(2));

        MvcResult htmlResult = mockMvc.perform(get("/api/reports/microservices/download")
                        .with(asViewerA())
                        .param("format", "html")
                        .param("from", from)
                        .param("to", to))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(".html")))
                .andReturn();
        assertThat(htmlResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("<!doctype html>")
                .contains("ARGOS — Relatório Operacional de Microsserviços");

        MvcResult mdResult = mockMvc.perform(get("/api/reports/microservices/download")
                        .with(asViewerA())
                        .param("format", "md")
                        .param("from", from)
                        .param("to", to))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(".md")))
                .andReturn();
        assertThat(mdResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("# Relatório de Microsserviços — ARGOS")
                .contains("http.server.duration");

        mockMvc.perform(get("/api/reports/microservices/download")
                        .with(asViewerA())
                        .param("format", "xlsx")
                        .param("from", from)
                        .param("to", to))
                .andExpect(status().isBadRequest());
    }
}
