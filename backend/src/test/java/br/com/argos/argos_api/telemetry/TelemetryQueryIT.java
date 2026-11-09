package br.com.argos.argos_api.telemetry;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static br.com.argos.argos_api.telemetry.OtlpPayloads.TRACE_ID;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TelemetryQueryIT extends AbstractIntegrationTest {

    private static final String OK_TRACE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private String keyA;
    private String keyB;

    @BeforeEach
    void sendTelemetry() throws Exception {
        keyA = createKey(asAdminA());
        keyB = createKey(asAdminB());
        // Org A: a failing trace crossing two services, a healthy one, logs and a metric.
        ingest(keyA, "traces", OtlpPayloads.traces("frontend",
                OtlpPayloads.span(TRACE_ID, "0000000000000001", null, "GET /checkout", now.minusSeconds(60), 900, true)));
        ingest(keyA, "traces", OtlpPayloads.traces("cartservice",
                OtlpPayloads.span(TRACE_ID, "0000000000000002", "0000000000000001", "redis GET",
                        now.minusSeconds(60), 800, true)));
        ingest(keyA, "traces", OtlpPayloads.traces("frontend",
                OtlpPayloads.span(OK_TRACE, "0000000000000003", null, "GET /", now.minusSeconds(30), 20, false)));
        ingest(keyA, "logs", OtlpPayloads.logs("cartservice",
                OtlpPayloads.logRecord(now.minusSeconds(59), 17, "ERROR", "redis timeout", TRACE_ID),
                OtlpPayloads.logRecord(now.minusSeconds(58), 9, "INFO", "carrinho lido", null)));
        ingest(keyA, "metrics", OtlpPayloads.metrics("cartservice", now.minusSeconds(10), "process.heap", 2048));
        // Org B: data that A must never see.
        ingest(keyB, "traces", OtlpPayloads.traces("segredo-b",
                OtlpPayloads.span("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "0000000000000009", null, "GET /b",
                        now.minusSeconds(30), 5, true)));
        ingest(keyB, "logs", OtlpPayloads.logs("segredo-b",
                OtlpPayloads.logRecord(now.minusSeconds(30), 17, "ERROR", "log de B", null)));
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
    void servicesSummarizeSpansLogsAndErrors() throws Exception {
        mockMvc.perform(get("/api/telemetry/services").with(asViewerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].service").value(contains("cartservice", "frontend")))
                .andExpect(jsonPath("$[?(@.service=='cartservice')].errorSpans").value(contains(1)))
                .andExpect(jsonPath("$[?(@.service=='cartservice')].errorLogs").value(contains(1)))
                .andExpect(jsonPath("$[?(@.service=='frontend')].spans").value(contains(2)));
    }

    @Test
    void tracesListSummariesAndFilterByErrorAndService() throws Exception {
        mockMvc.perform(get("/api/telemetry/traces").with(asViewerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].traceId").value(OK_TRACE));

        mockMvc.perform(get("/api/telemetry/traces").param("status", "ERROR").with(asViewerA()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].traceId").value(TRACE_ID))
                .andExpect(jsonPath("$[0].rootService").value("frontend"))
                .andExpect(jsonPath("$[0].rootName").value("GET /checkout"))
                .andExpect(jsonPath("$[0].spanCount").value(2))
                .andExpect(jsonPath("$[0].errorCount").value(2))
                .andExpect(jsonPath("$[0].durationMs").value(900.0))
                .andExpect(jsonPath("$[0].services").value(contains("cartservice", "frontend")));

        // Filtering by the downstream service still returns the whole trace.
        mockMvc.perform(get("/api/telemetry/traces").param("service", "cartservice").with(asViewerA()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].spanCount").value(2));

        mockMvc.perform(get("/api/telemetry/traces").param("minDurationMs", "500").with(asViewerA()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].traceId").value(TRACE_ID));
    }

    @Test
    void traceDetailReturnsAllSpansWithParentLinks() throws Exception {
        mockMvc.perform(get("/api/telemetry/traces/" + TRACE_ID).with(asEditorA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spans", hasSize(2)))
                .andExpect(jsonPath("$.spans[0].name").value("GET /checkout"))
                .andExpect(jsonPath("$.spans[0].parentSpanId").doesNotExist())
                .andExpect(jsonPath("$.spans[1].parentSpanId").value("0000000000000001"))
                .andExpect(jsonPath("$.spans[1].error").value(true))
                .andExpect(jsonPath("$.spans[1].attributes['http.route']").value("/cart"));

        mockMvc.perform(get("/api/telemetry/traces/" + "c".repeat(32)).with(asEditorA()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/telemetry/traces/curto").with(asEditorA()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logsFilterByLevelTraceAndText() throws Exception {
        mockMvc.perform(get("/api/telemetry/logs").with(asViewerA()))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].body").value("carrinho lido"));
        mockMvc.perform(get("/api/telemetry/logs").param("level", "error").with(asViewerA()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].traceId").value(TRACE_ID));
        mockMvc.perform(get("/api/telemetry/logs").param("traceId", TRACE_ID).with(asViewerA()))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/telemetry/logs").param("q", "REDIS").with(asViewerA()))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/telemetry/logs").param("level", "pavao").with(asViewerA()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void metricsListSeriesAndPoints() throws Exception {
        mockMvc.perform(get("/api/telemetry/metrics/names").with(asViewerA()))
                .andExpect(jsonPath("$[*].name").value(contains("http.server.duration.count",
                        "http.server.duration.sum", "process.heap")));
        mockMvc.perform(get("/api/telemetry/metrics").param("name", "process.heap").with(asViewerA()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].value").value(2048.0));
        mockMvc.perform(get("/api/telemetry/metrics").with(asViewerA())).andExpect(status().isBadRequest());
    }

    @Test
    void organizationsNeverSeeEachOthersTelemetry() throws Exception {
        mockMvc.perform(get("/api/telemetry/services").with(asAdminA()))
                .andExpect(jsonPath("$[?(@.service=='segredo-b')]").isEmpty());
        mockMvc.perform(get("/api/telemetry/traces").with(asAdminA()))
                .andExpect(jsonPath("$[?(@.rootService=='segredo-b')]").isEmpty());
        mockMvc.perform(get("/api/telemetry/traces/" + "b".repeat(32)).with(asAdminA()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/telemetry/logs").param("q", "log de B").with(asAdminA()))
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/telemetry/services").with(asAdminB()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].service").value("segredo-b"));
        mockMvc.perform(get("/api/telemetry/traces/" + TRACE_ID).with(asAdminB()))
                .andExpect(status().isNotFound());
    }

    @Test
    void timeWindowAndAuthenticationAreEnforced() throws Exception {
        mockMvc.perform(get("/api/telemetry/traces")
                        .param("from", now.minus(2, ChronoUnit.HOURS).toString())
                        .param("to", now.minus(1, ChronoUnit.HOURS).toString()).with(asViewerA()))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/telemetry/traces")
                        .param("from", now.toString()).param("to", now.minusSeconds(5).toString()).with(asViewerA()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/telemetry/services")).andExpect(status().isUnauthorized());
    }
}
