package br.com.argos.argos_api.ai;

import br.com.argos.argos_api.ai.investigation.Investigation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
class InvestigationController {

    /** Investigation without tenant internals. */
    record InvestigationView(UUID id, UUID incidentId, String status, String trigger, String mode, int attempts,
                             Instant runAfter, Object report, List<?> steps, List<?> guardrailEvents, List<?> llmCalls,
                             String model, boolean fallbackUsed, int inputTokens, int outputTokens, int cacheReadTokens,
                             int cacheWriteTokens, BigDecimal costUsd, int toolCalls, String error, Instant createdAt,
                             Instant startedAt, Instant finishedAt) {

        static InvestigationView from(Investigation i) {
            return new InvestigationView(i.id(), i.incidentId(), i.status(), i.trigger(), i.mode(), i.attempts(),
                    i.runAfter(), i.report(), i.steps(), i.guardrailEvents(), i.llmCalls(), i.model(), i.fallbackUsed(),
                    i.inputTokens(), i.outputTokens(), i.cacheReadTokens(), i.cacheWriteTokens(), i.costUsd(),
                    i.toolCalls(), i.error(), i.createdAt(), i.startedAt(), i.finishedAt());
        }
    }

    private final InvestigationService investigations;

    InvestigationController(InvestigationService investigations) {
        this.investigations = investigations;
    }

    /** Latest investigation of the incident, or 204 when it was never investigated. */
    @GetMapping("/api/incidents/{id}/investigation")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    ResponseEntity<InvestigationView> latest(@PathVariable UUID id) {
        return investigations.latest(id).map(InvestigationView::from).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/api/incidents/{id}/investigations")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    List<InvestigationView> history(@PathVariable UUID id) {
        return investigations.history(id).stream().map(InvestigationView::from).toList();
    }

    /** 202: the investigation runs in the background; poll the GET above. */
    @PostMapping("/api/incidents/{id}/investigation")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    ResponseEntity<InvestigationView> request(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(InvestigationView.from(investigations.requestManual(id)));
    }

    /** Console home: active incidents with the AI's conclusion and the estimated business cost. */
    @GetMapping("/api/ai/active-incidents")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    List<InvestigationService.ActiveIncident> activeIncidents(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "8") int limit) {
        return investigations.activeIncidents(limit);
    }

    @GetMapping("/api/ai/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    InvestigationService.AiStatus status() {
        return investigations.status();
    }
}
