package br.com.argos.argos_api.notification;

import br.com.argos.argos_api.notification.dto.NotificationChannelRequest;
import br.com.argos.argos_api.notification.dto.NotificationChannelResponse;
import br.com.argos.argos_api.notification.dto.NotificationTestResult;
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
@RequestMapping("/api/notifications/channels")
@PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
public class NotificationController {

    private static final String WRITE = "hasAnyRole('ADMIN', 'EDITOR')";

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<NotificationChannelResponse> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationChannelResponse create(@Valid @RequestBody NotificationChannelRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    public NotificationChannelResponse update(@PathVariable UUID id,
                                              @Valid @RequestBody NotificationChannelRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/test")
    @PreAuthorize(WRITE)
    public NotificationTestResult test(@PathVariable UUID id) {
        return service.testChannel(id);
    }
}
