package br.com.argos.argos_api.telemetry;

import br.com.argos.argos_api.apikey.ApiKeyPrincipal;
import br.com.argos.argos_api.telemetry.ParsedTelemetry.Batch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

@Service
class TelemetryIngestService {

    private final OtlpParser parser;
    private final TelemetryStore store;
    private final Clock clock;

    TelemetryIngestService(OtlpParser parser, TelemetryStore store, Clock clock) {
        this.parser = parser;
        this.store = store;
        this.clock = clock;
    }

    /** The organization always comes from the API key, never from the payload. */
    @Transactional
    Map<String, Object> ingestTraces(ApiKeyPrincipal key, String body) {
        Batch<ParsedTelemetry.Span> batch = parser.parseTraces(body);
        store.saveSpans(key.organizationId(), batch.accepted(), clock.instant());
        return partialSuccess("rejectedSpans", batch.rejected());
    }

    @Transactional
    Map<String, Object> ingestLogs(ApiKeyPrincipal key, String body) {
        Batch<ParsedTelemetry.Log> batch = parser.parseLogs(body);
        store.saveLogs(key.organizationId(), batch.accepted(), clock.instant());
        return partialSuccess("rejectedLogRecords", batch.rejected());
    }

    @Transactional
    Map<String, Object> ingestMetrics(ApiKeyPrincipal key, String body) {
        Batch<ParsedTelemetry.Metric> batch = parser.parseMetrics(body);
        store.saveMetrics(key.organizationId(), batch.accepted(), clock.instant());
        return partialSuccess("rejectedDataPoints", batch.rejected());
    }

    /** OTLP answers 200 with an empty partialSuccess when everything was accepted. */
    private static Map<String, Object> partialSuccess(String rejectedField, int rejected) {
        return rejected == 0
                ? Map.of("partialSuccess", Map.of())
                : Map.of("partialSuccess", Map.of(rejectedField, rejected));
    }
}
