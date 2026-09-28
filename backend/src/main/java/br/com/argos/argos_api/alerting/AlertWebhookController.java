package br.com.argos.argos_api.alerting;

import br.com.argos.argos_api.alerting.domain.AlertSource;
import br.com.argos.argos_api.alerting.dto.WebhookResult;
import br.com.argos.argos_api.alerting.mapping.AlertMapper;
import br.com.argos.argos_api.alerting.mapping.ArgosDefaultMapping;
import br.com.argos.argos_api.apikey.ApiKeyPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;


@RestController
@RequestMapping("/api/webhooks/alerts")
public class AlertWebhookController {

    static final int MAX_BODY_BYTES = 1_048_576;

    private final AlertMapper alertMapper;
    private final AlertProcessor alertProcessor;
    private final AlertSourceService alertSourceService;

    public AlertWebhookController(AlertMapper alertMapper, AlertProcessor alertProcessor,
                                  AlertSourceService alertSourceService) {
        this.alertMapper = alertMapper;
        this.alertProcessor = alertProcessor;
        this.alertSourceService = alertSourceService;
    }

    @PostMapping
    public WebhookResult receive(@AuthenticationPrincipal ApiKeyPrincipal key,
                                 @RequestBody(required = false) String body) {
        checkSize(body);
        return alertProcessor.process(key, null, ArgosDefaultMapping.SOURCE_NAME,
                alertMapper.map(body, ArgosDefaultMapping.MAPPING));
    }

    @PostMapping("/{sourceId}")
    public WebhookResult receiveFromSource(@AuthenticationPrincipal ApiKeyPrincipal key,
                                           @PathVariable UUID sourceId,
                                           @RequestBody(required = false) String body) {
        checkSize(body);
        AlertSource source = alertSourceService.findActive(key.organizationId(), sourceId);
        return alertProcessor.process(key, source.getId(), source.getName(),
                alertMapper.map(body, source.toMapping()));
    }

    static void checkSize(String body) {
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new PayloadTooLargeException(MAX_BODY_BYTES);
        }
    }
}
