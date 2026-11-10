package br.com.argos.argos_api.telemetry;

import br.com.argos.argos_api.telemetry.TelemetryViews.LogView;
import br.com.argos.argos_api.telemetry.TelemetryViews.MetricPoint;
import br.com.argos.argos_api.telemetry.TelemetryViews.MetricSeries;
import br.com.argos.argos_api.telemetry.TelemetryViews.RedSeries;
import br.com.argos.argos_api.telemetry.TelemetryViews.ServiceSummary;
import br.com.argos.argos_api.telemetry.TelemetryViews.ServiceTopology;
import br.com.argos.argos_api.telemetry.TelemetryViews.TelemetryRuleRequest;
import br.com.argos.argos_api.telemetry.TelemetryViews.TelemetryRuleView;
import br.com.argos.argos_api.telemetry.TelemetryViews.TraceDetail;
import br.com.argos.argos_api.telemetry.TelemetryViews.TraceSummary;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/telemetry")
@PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
public class TelemetryController {

    private static final String WRITE = "hasAnyRole('ADMIN', 'EDITOR')";

    private final TelemetryQueryService queries;
    private final TelemetryMonitor monitor;

    public TelemetryController(TelemetryQueryService queries, TelemetryMonitor monitor) {
        this.queries = queries;
        this.monitor = monitor;
    }

    @GetMapping("/services")
    public List<ServiceSummary> services(@RequestParam(required = false) Instant from,
                                         @RequestParam(required = false) Instant to) {
        return queries.services(from, to);
    }

    @GetMapping("/topology")
    public ServiceTopology topology(@RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to) {
        return queries.topology(from, to);
    }

    @GetMapping("/red")
    public RedSeries red(@RequestParam(required = false) String service,
                         @RequestParam(required = false) Instant from,
                         @RequestParam(required = false) Instant to) {
        return queries.red(service, from, to);
    }

    @GetMapping("/rules")
    public List<TelemetryRuleView> listRules() {
        return monitor.listRules();
    }

    @PostMapping("/rules")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public TelemetryRuleView saveRule(@RequestBody TelemetryRuleRequest request) {
        return monitor.saveRule(request);
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize(WRITE)
    public TelemetryRuleView updateRule(@PathVariable UUID id, @RequestBody TelemetryRuleRequest request) {
        return monitor.updateRule(id, request);
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID id) {
        monitor.deleteRule(id);
    }

    @GetMapping("/traces")
    public List<TraceSummary> traces(@RequestParam(required = false) String service,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) Long minDurationMs,
                                     @RequestParam(required = false) Instant from,
                                     @RequestParam(required = false) Instant to,
                                     @RequestParam(required = false) Integer limit) {
        boolean onlyErrors = status != null && !status.isBlank();
        if (onlyErrors && !"ERROR".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("status aceita apenas ERROR");
        }
        return queries.traces(service, onlyErrors, minDurationMs, from, to, limit);
    }

    @GetMapping("/traces/{traceId}")
    public TraceDetail trace(@PathVariable String traceId) {
        return queries.trace(traceId);
    }

    @GetMapping("/logs")
    public List<LogView> logs(@RequestParam(required = false) String service,
                              @RequestParam(required = false) String level,
                              @RequestParam(required = false) String traceId,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) Instant from,
                              @RequestParam(required = false) Instant to,
                              @RequestParam(required = false) Integer limit) {
        return queries.logs(service, level, traceId, q, from, to, limit);
    }

    @GetMapping("/metrics/names")
    public List<MetricSeries> metricSeries(@RequestParam(required = false) Instant from,
                                           @RequestParam(required = false) Instant to) {
        return queries.metricSeries(from, to);
    }

    @GetMapping("/metrics")
    public List<MetricPoint> metrics(@RequestParam String name,
                                     @RequestParam(required = false) String service,
                                     @RequestParam(required = false) Instant from,
                                     @RequestParam(required = false) Instant to,
                                     @RequestParam(required = false) Integer limit) {
        return queries.metricPoints(name, service, from, to, limit);
    }
}
