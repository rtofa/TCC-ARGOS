package br.com.argos.argos_api.telemetry;

import br.com.argos.argos_api.telemetry.ParsedTelemetry.Batch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static br.com.argos.argos_api.telemetry.OtlpPayloads.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtlpParserTest {

    private final OtlpParser parser = new OtlpParser();
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Test
    void parsesSpanWithServiceDurationStatusAndTypedAttributes() {
        String body = OtlpPayloads.traces("cartservice",
                OtlpPayloads.span(TRACE_ID, "051581bf3cb55c13", null, "GET /cart", now, 250, true));

        Batch<ParsedTelemetry.Span> batch = parser.parseTraces(body);

        assertThat(batch.rejected()).isZero();
        assertThat(batch.accepted()).singleElement().satisfies(span -> {
            assertThat(span.service()).isEqualTo("cartservice");
            assertThat(span.traceId()).isEqualTo(TRACE_ID);
            assertThat(span.parentSpanId()).isNull();
            assertThat(span.startTime()).isEqualTo(now);
            assertThat(span.durationNs()).isEqualTo(250_000_000L);
            assertThat(span.statusCode()).isEqualTo(2);
            assertThat(span.statusMessage()).isEqualTo("boom");
            assertThat(span.attributes()).containsEntry("http.route", "/cart").containsEntry("http.status_code", 500L);
        });
    }

    @Test
    void acceptsStatusCodeSentAsEnumName() {
        String body = """
                {"resourceSpans":[{"scopeSpans":[{"spans":[{"traceId":"%s","spanId":"051581bf3cb55c13","name":"x",
                 "startTimeUnixNano":"%s","endTimeUnixNano":"%s","status":{"code":"STATUS_CODE_ERROR"}}]}]}]}
                """.formatted(TRACE_ID, OtlpPayloads.nanos(now), OtlpPayloads.nanos(now.plusMillis(1)));

        Batch<ParsedTelemetry.Span> batch = parser.parseTraces(body);

        assertThat(batch.accepted()).singleElement().satisfies(span -> {
            assertThat(span.statusCode()).isEqualTo(2);
            assertThat(span.service()).isEqualTo(OtlpParser.UNKNOWN_SERVICE);
        });
    }

    @Test
    void dropsAndCountsMalformedSpansWithoutFailingTheBatch() {
        String valid = OtlpPayloads.span(TRACE_ID, "051581bf3cb55c13", null, "ok", now, 5, false);
        String badTrace = OtlpPayloads.span("nao-e-hex", "051581bf3cb55c14", null, "ruim", now, 5, false);
        String zeroSpan = OtlpPayloads.span(TRACE_ID, "0000000000000000", null, "zero", now, 5, false);

        Batch<ParsedTelemetry.Span> batch = parser.parseTraces(OtlpPayloads.traces("svc", valid, badTrace, zeroSpan));

        assertThat(batch.accepted()).hasSize(1);
        assertThat(batch.rejected()).isEqualTo(2);
    }

    @Test
    void parsesLogsWithSeverityAndTraceCorrelation() {
        String body = OtlpPayloads.logs("cartservice",
                OtlpPayloads.logRecord(now, 17, "ERROR", "falha ao ler carrinho", TRACE_ID),
                OtlpPayloads.logRecord(now, 9, "INFO", "ok", null));

        Batch<ParsedTelemetry.Log> batch = parser.parseLogs(body);

        assertThat(batch.accepted()).hasSize(2);
        assertThat(batch.accepted().get(0).severityNumber()).isEqualTo(17);
        assertThat(batch.accepted().get(0).traceId()).isEqualTo(TRACE_ID);
        assertThat(batch.accepted().get(1).traceId()).isNull();
    }

    @Test
    void truncatesVeryLongLogBodies() {
        String body = OtlpPayloads.logs("svc",
                OtlpPayloads.logRecord(now, 9, "INFO", "x".repeat(OtlpParser.MAX_LOG_BODY_LENGTH + 500), null));

        assertThat(parser.parseLogs(body).accepted().get(0).body()).hasSize(OtlpParser.MAX_LOG_BODY_LENGTH);
    }

    @Test
    void reducesHistogramToCountAndSum() {
        Batch<ParsedTelemetry.Metric> batch = parser.parseMetrics(OtlpPayloads.metrics("svc", now, "process.cpu", 0.5));

        assertThat(batch.accepted()).extracting(ParsedTelemetry.Metric::name)
                .containsExactly("process.cpu", "http.server.duration.count", "http.server.duration.sum");
        assertThat(batch.accepted().get(1).value()).isEqualTo(4);
        assertThat(batch.accepted().get(2).value()).isEqualTo(120.5);
    }

    @Test
    void rejectsDocumentsThatAreNotJsonObjects() {
        assertThatThrownBy(() -> parser.parseTraces("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parseTraces("{nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parseTraces("[1]")).isInstanceOf(IllegalArgumentException.class);
    }
}
