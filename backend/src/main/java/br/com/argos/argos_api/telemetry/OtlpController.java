package br.com.argos.argos_api.telemetry;

import br.com.argos.argos_api.apikey.ApiKeyPrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * OTLP/HTTP receiver. Point the OpenTelemetry Collector's {@code otlphttp} exporter (with
 * {@code encoding: json}) at {@code /api/webhooks/otlp}; it appends {@code /v1/traces}, {@code /v1/logs}
 * and {@code /v1/metrics}. Authenticated by API key like every /api/webhooks/** route.
 */
@RestController
@RequestMapping(value = "/api/webhooks/otlp/v1", consumes = MediaType.APPLICATION_JSON_VALUE)
public class OtlpController {

    static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

    private final TelemetryIngestService ingestService;

    OtlpController(TelemetryIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/traces")
    public Map<String, Object> traces(@AuthenticationPrincipal ApiKeyPrincipal key,
                                      @RequestBody(required = false) String body) {
        checkSize(body);
        return ingestService.ingestTraces(key, body);
    }

    @PostMapping("/logs")
    public Map<String, Object> logs(@AuthenticationPrincipal ApiKeyPrincipal key,
                                    @RequestBody(required = false) String body) {
        checkSize(body);
        return ingestService.ingestLogs(key, body);
    }

    @PostMapping("/metrics")
    public Map<String, Object> metrics(@AuthenticationPrincipal ApiKeyPrincipal key,
                                       @RequestBody(required = false) String body) {
        checkSize(body);
        return ingestService.ingestMetrics(key, body);
    }

    private static void checkSize(String body) {
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new PayloadTooLargeException(MAX_BODY_BYTES);
        }
    }
}
