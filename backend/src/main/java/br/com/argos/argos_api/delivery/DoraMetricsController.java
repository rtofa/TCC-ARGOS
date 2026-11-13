package br.com.argos.argos_api.delivery;

import br.com.argos.argos_api.delivery.dto.DoraMetricsResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/metrics/dora")
@PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
public class DoraMetricsController {

    private final DoraMetricsService doraMetricsService;

    public DoraMetricsController(DoraMetricsService doraMetricsService) {
        this.doraMetricsService = doraMetricsService;
    }

    @GetMapping
    public DoraMetricsResponse get(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) Integer failureWindowMinutes) {
        return doraMetricsService.calculate(from, to, service, failureWindowMinutes);
    }
}
