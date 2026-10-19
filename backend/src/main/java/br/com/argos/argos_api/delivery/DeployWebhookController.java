package br.com.argos.argos_api.delivery;

import br.com.argos.argos_api.apikey.ApiKeyPrincipal;
import br.com.argos.argos_api.delivery.dto.DeployResponse;
import br.com.argos.argos_api.delivery.dto.RecordDeployRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by CI/CD pipelines; authenticated by API key like every /api/webhooks/** route.
 */
@RestController
@RequestMapping("/api/webhooks/deploys")
public class DeployWebhookController {

    private final DeployService deployService;

    public DeployWebhookController(DeployService deployService) {
        this.deployService = deployService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeployResponse record(@AuthenticationPrincipal ApiKeyPrincipal key,
                                 @Valid @RequestBody RecordDeployRequest request) {
        return deployService.record(key, request);
    }
}
