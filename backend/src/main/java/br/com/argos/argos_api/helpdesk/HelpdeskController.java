package br.com.argos.argos_api.helpdesk;

import br.com.argos.argos_api.helpdesk.model.IngestionReport;
import br.com.argos.argos_api.helpdesk.model.KnowledgeStatus;
import br.com.argos.argos_api.helpdesk.model.SupportAnswer;
import br.com.argos.argos_api.helpdesk.model.SupportRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

/** Support agent API: questions about the platform answered from the official documentation. */
@RestController
@RequestMapping("/api/support")
class HelpdeskController {

    private static final String ANY_ROLE = "hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')";

    private final SupportService service;

    HelpdeskController(SupportService service) {
        this.service = service;
    }

    /** Full answer as JSON (the request thread is released while the agent works). */
    @PostMapping("/ask")
    @PreAuthorize(ANY_ROLE)
    CompletableFuture<SupportAnswer> ask(@Valid @RequestBody SupportRequest request) {
        return service.ask(request);
    }

    /** Same answer streamed as server-sent events (stage, sources, token…, done | error). */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize(ANY_ROLE)
    ResponseEntity<SseEmitter> stream(@Valid @RequestBody SupportRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no") // nginx: deliver events as they come
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(service.stream(request));
    }

    @GetMapping("/knowledge")
    @PreAuthorize(ANY_ROLE)
    KnowledgeStatus status() {
        return service.status();
    }

    /** Indexes new and changed documents now ({@code force=true} rebuilds every document). */
    @PostMapping("/knowledge/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    CompletableFuture<IngestionReport> reindex(@RequestParam(defaultValue = "false") boolean force) {
        return service.reindex(force);
    }
}
