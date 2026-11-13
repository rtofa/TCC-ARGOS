package br.com.argos.argos_api.delivery;

import br.com.argos.argos_api.apikey.ApiKeyPrincipal;
import br.com.argos.argos_api.delivery.dto.DeployResponse;
import br.com.argos.argos_api.delivery.dto.QualityGateResponse;
import br.com.argos.argos_api.delivery.dto.RecordDeployRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by CI/CD pipelines; authenticated by API key like every /api/webhooks/** route.
 */
@RestController
@RequestMapping("/api/webhooks/deploys")
public class DeployWebhookController {

    private final DeployService deployService;
    private final QualityGateService qualityGateService;

    public DeployWebhookController(DeployService deployService, QualityGateService qualityGateService) {
        this.deployService = deployService;
        this.qualityGateService = qualityGateService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeployResponse record(@AuthenticationPrincipal ApiKeyPrincipal key,
                                 @Valid @RequestBody RecordDeployRequest request) {
        return deployService.record(key, request);
    }

    @GetMapping("/quality-gate")
    public ResponseEntity<QualityGateResponse> qualityGate(
            @AuthenticationPrincipal ApiKeyPrincipal key,
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "false") boolean strict) {
        QualityGateResponse result = qualityGateService.evaluate(key.organizationId(), service);
        if (strict && !result.allowed()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        }
        return ResponseEntity.ok(result);
    }
}
