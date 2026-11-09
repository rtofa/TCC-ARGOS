package br.com.argos.argos_api.telemetry;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static br.com.argos.argos_api.telemetry.OtlpPayloads.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OtlpIngestIT extends AbstractIntegrationTest {

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private String keyA;
    private String keyB;

    @BeforeEach
    void createKeys() throws Exception {
        keyA = createKey(asAdminA());
        keyB = createKey(asAdminB());
    }

    private String createKey(RequestPostProcessor admin) throws Exception {
        String created = mockMvc.perform(post("/api/api-keys").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"OTel Collector\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.key");
    }

    private ResultActions send(String signal, String key, String body) throws Exception {
        var request = post("/api/webhooks/otlp/v1/" + signal).contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(key == null ? request : request.header("X-API-Key", key));
    }

    private int count(String table, java.util.UUID organization) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE organization_id = ?", Integer.class,
                organization);
    }

    @Test
    void storesTracesLogsAndMetricsUnderTheOrganizationOfTheKey() throws Exception {
        send("traces", keyA, OtlpPayloads.traces("cartservice",
                OtlpPayloads.span(TRACE_ID, "051581bf3cb55c13", null, "GET /cart", now, 120, false),
                OtlpPayloads.span(TRACE_ID, "051581bf3cb55c14", "051581bf3cb55c13", "redis GET", now, 30, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partialSuccess").isEmpty());
        send("logs", keyA, OtlpPayloads.logs("cartservice",
                OtlpPayloads.logRecord(now, 17, "ERROR", "redis timeout", TRACE_ID)))
                .andExpect(status().isOk());
        send("metrics", keyA, OtlpPayloads.metrics("cartservice", now, "process.runtime.heap", 1024))
                .andExpect(status().isOk());

        assertThat(count("telemetry_spans", orgA)).isEqualTo(2);
        assertThat(count("telemetry_logs", orgA)).isEqualTo(1);
        assertThat(count("telemetry_metrics", orgA)).isEqualTo(3);
        assertThat(count("telemetry_spans", orgB)).isZero();
        assertThat(count("telemetry_logs", orgB)).isZero();
        assertThat(count("telemetry_metrics", orgB)).isZero();
    }

    @Test
    void resendingTheSameBatchDoesNotDuplicateSpans() throws Exception {
        String body = OtlpPayloads.traces("cartservice",
                OtlpPayloads.span(TRACE_ID, "051581bf3cb55c13", null, "GET /cart", now, 120, false));

        send("traces", keyA, body).andExpect(status().isOk());
        send("traces", keyA, body).andExpect(status().isOk());

        assertThat(count("telemetry_spans", orgA)).isEqualTo(1);
    }

    @Test
    void sameSpanIdInAnotherOrganizationIsStoredSeparately() throws Exception {
        String body = OtlpPayloads.traces("cartservice",
                OtlpPayloads.span(TRACE_ID, "051581bf3cb55c13", null, "GET /cart", now, 120, false));

        send("traces", keyA, body).andExpect(status().isOk());
        send("traces", keyB, body).andExpect(status().isOk());

        assertThat(count("telemetry_spans", orgA)).isEqualTo(1);
        assertThat(count("telemetry_spans", orgB)).isEqualTo(1);
    }

    @Test
    void reportsRejectedSpansButKeepsTheValidOnes() throws Exception {
        send("traces", keyA, OtlpPayloads.traces("svc",
                OtlpPayloads.span(TRACE_ID, "051581bf3cb55c13", null, "ok", now, 5, false),
                OtlpPayloads.span("invalido", "051581bf3cb55c14", null, "ruim", now, 5, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partialSuccess.rejectedSpans").value(1));

        assertThat(count("telemetry_spans", orgA)).isEqualTo(1);
    }

    @Test
    void requiresAValidApiKey() throws Exception {
        String body = OtlpPayloads.traces("svc", OtlpPayloads.span(TRACE_ID, "051581bf3cb55c13", null, "x", now, 5, false));

        send("traces", null, body).andExpect(status().isUnauthorized());
        send("traces", "argos_chave_invalida", body).andExpect(status().isUnauthorized());
        // A user JWT must not work on a webhook route.
        mockMvc.perform(post("/api/webhooks/otlp/v1/traces").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThat(count("telemetry_spans", orgA)).isZero();
    }

    @Test
    void rejectsMalformedJsonAndProtobuf() throws Exception {
        send("traces", keyA, "{nope").andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/webhooks/otlp/v1/traces").header("X-API-Key", keyA)
                        .contentType("application/x-protobuf").content(new byte[]{1, 2, 3}))
                .andExpect(status().isUnsupportedMediaType());
    }
}
