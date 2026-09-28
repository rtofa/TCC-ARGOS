package br.com.argos.argos_api.apikey;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
public class ApiKeyPingController {

    @GetMapping("/api/webhooks/ping")
    public Map<String, Object> ping(@AuthenticationPrincipal ApiKeyPrincipal principal) {
        return Map.of("organizationId", principal.organizationId(), "keyPrefix", principal.prefix());
    }
}
