package br.com.argos.argos_api.alerting;

import br.com.argos.argos_api.alerting.dto.AlertSourceRequest;
import br.com.argos.argos_api.alerting.dto.AlertSourceResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/alert-sources")
public class AlertSourceController {

    private static final String READ = "hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')";
    private static final String WRITE = "hasAnyRole('ADMIN', 'EDITOR')";

    private final AlertSourceService alertSourceService;

    public AlertSourceController(AlertSourceService alertSourceService) {
        this.alertSourceService = alertSourceService;
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public AlertSourceResponse create(@Valid @RequestBody AlertSourceRequest request) {
        return alertSourceService.create(request);
    }

    @GetMapping
    @PreAuthorize(READ)
    public List<AlertSourceResponse> list() {
        return alertSourceService.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public AlertSourceResponse get(@PathVariable UUID id) {
        return alertSourceService.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    public AlertSourceResponse update(@PathVariable UUID id, @Valid @RequestBody AlertSourceRequest request) {
        return alertSourceService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        alertSourceService.delete(id);
    }
}
