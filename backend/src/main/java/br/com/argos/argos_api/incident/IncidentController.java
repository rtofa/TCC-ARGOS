package br.com.argos.argos_api.incident;

import br.com.argos.argos_api.incident.domain.IncidentStatus;
import br.com.argos.argos_api.incident.domain.Severity;
import br.com.argos.argos_api.incident.dto.AssignIncidentRequest;
import br.com.argos.argos_api.incident.dto.PageResponse;
import br.com.argos.argos_api.incident.dto.CommentRequest;
import br.com.argos.argos_api.incident.dto.CreateIncidentRequest;
import br.com.argos.argos_api.incident.dto.IncidentEventResponse;
import br.com.argos.argos_api.incident.dto.IncidentMetricsResponse;
import br.com.argos.argos_api.incident.dto.IncidentResponse;
import br.com.argos.argos_api.incident.dto.UpdateIncidentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private static final String READ = "hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')";
    private static final String WRITE = "hasAnyRole('ADMIN', 'EDITOR')";

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<IncidentResponse> create(@Valid @RequestBody CreateIncidentRequest request) {
        IncidentResponse created = incidentService.create(request);
        return ResponseEntity.created(URI.create("/api/incidents/" + created.id())).body(created);
    }

    @GetMapping
    @PreAuthorize(READ)
    public PageResponse<IncidentResponse> list(
            @RequestParam(required = false) Set<IncidentStatus> status,
            @RequestParam(required = false) Set<Severity> severity,
            @RequestParam(required = false) Instant openedFrom,
            @RequestParam(required = false) Instant openedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return incidentService.list(status, severity, openedFrom, openedTo, page, size);
    }

    @GetMapping("/metrics")
    @PreAuthorize(READ)
    public IncidentMetricsResponse metrics(
            @RequestParam(required = false) Instant openedFrom,
            @RequestParam(required = false) Instant openedTo,
            @RequestParam(required = false) String groupBy) {
        return incidentService.metrics(openedFrom, openedTo, groupBy);
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public IncidentResponse get(@PathVariable UUID id) {
        return incidentService.get(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize(WRITE)
    public IncidentResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateIncidentRequest request) {
        return incidentService.update(id, request);
    }

    @GetMapping("/{id}/timeline")
    @PreAuthorize(READ)
    public List<IncidentEventResponse> timeline(@PathVariable UUID id) {
        return incidentService.timeline(id);
    }

    @PostMapping("/{id}/comments")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentEventResponse comment(@PathVariable UUID id, @Valid @RequestBody CommentRequest request) {
        return incidentService.comment(id, request);
    }

    @PutMapping("/{id}/assignee")
    @PreAuthorize(WRITE)
    public IncidentResponse assign(@PathVariable UUID id, @RequestBody AssignIncidentRequest request) {
        return incidentService.assign(id, request);
    }
}
