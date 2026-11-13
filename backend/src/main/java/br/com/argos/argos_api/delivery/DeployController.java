package br.com.argos.argos_api.delivery;

import br.com.argos.argos_api.delivery.dto.DeployPageResponse;
import br.com.argos.argos_api.delivery.dto.QualityGateResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

@RestController
@RequestMapping("/api/deploys")
public class DeployController {

    private final DeployService deployService;
    private final QualityGateService qualityGateService;

    public DeployController(DeployService deployService, QualityGateService qualityGateService) {
        this.deployService = deployService;
        this.qualityGateService = qualityGateService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    public DeployPageResponse list(
            @RequestParam(required = false) Set<String> service,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return deployService.list(service, environment, from, to, page, size);
    }

    @GetMapping("/quality-gate")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    public QualityGateResponse qualityGate(@RequestParam(required = false) String service) {
        return qualityGateService.evaluateForCurrentUser(service);
    }
}
