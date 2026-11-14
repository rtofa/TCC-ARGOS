package br.com.argos.argos_api.ai;

import br.com.argos.argos_api.ai.investigation.Investigation;
import br.com.argos.argos_api.ai.investigation.InvestigationRepository;
import br.com.argos.argos_api.ai.llm.LlmRouter;
import br.com.argos.argos_api.business.BusinessImpactService;
import br.com.argos.argos_api.incident.IncidentQueries;
import br.com.argos.argos_api.shared.security.AuthenticatedUser;
import br.com.argos.argos_api.shared.security.CurrentUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Entry point of AI investigations: manual and automatic requests, and the read side for the console. */
@Service
public class InvestigationService {

    /** {@code routes} is the live chain (with circuit state); {@code configuredModels} shows it even without a key. */
    public record AiStatus(boolean enabled, boolean llmConfigured, String mode, boolean autoInvestigate,
                           List<LlmRouter.RouteStatus> routes, List<String> configuredModels) {
    }
    private final InvestigationRepository repository;
    private final IncidentQueries incidents;
    private final CurrentUser currentUser;
    private final AiProperties properties;
    private final LlmRouter router;
    private final BusinessImpactService business;
    private final Clock clock;

    public InvestigationService(InvestigationRepository repository, IncidentQueries incidents, CurrentUser currentUser,
                                AiProperties properties, @Qualifier("investigationRouter") LlmRouter router,
                                BusinessImpactService business, Clock clock) {
        this.business = business;
        this.repository = repository;
        this.incidents = incidents;
        this.currentUser = currentUser;
        this.properties = properties;
        this.router = router;
        this.clock = clock;
    }

    /** Queues an investigation now; returns the active one instead when the incident already has one. */
    public Investigation requestManual(UUID incidentId) {
        AuthenticatedUser user = currentUser.get();
        requireIncident(user.organizationId(), incidentId);
        return repository.enqueue(user.organizationId(), incidentId, "MANUAL", user.userId(), clock.instant());
    }

    /** Called when an incident opens; waits a little so the alert burst and telemetry are in when the agent looks. */
    public void requestAutomatic(UUID organizationId, UUID incidentId) {
        if (!properties.isEnabled() || !properties.isAutoInvestigate()) {
            return;
        }
        repository.enqueue(organizationId, incidentId, "AUTO", null,
                clock.instant().plus(properties.getAutoInvestigateDelay()));
    }

    public Optional<Investigation> latest(UUID incidentId) {
        UUID organizationId = currentUser.get().organizationId();
        requireIncident(organizationId, incidentId);
        return repository.latest(organizationId, incidentId);
    }

    public List<Investigation> history(UUID incidentId) {
        UUID organizationId = currentUser.get().organizationId();
        requireIncident(organizationId, incidentId);
        return repository.history(organizationId, incidentId, 10);
    }

    /** One active incident on the console home, with what the AI concluded and what it is costing. */
    public record ActiveIncident(UUID id, String title, String severity, String status, List<String> services,
                                 java.time.Instant openedAt, InvestigationRepository.LatestSummary investigation,
                                 java.math.BigDecimal impactValue, String impactMethod) {
    }

    /** The most severe active incidents (newest first within a severity), at most {@code limit}. */
    public List<ActiveIncident> activeIncidents(int limit) {
        UUID organizationId = currentUser.get().organizationId();
        java.time.Instant now = clock.instant();
        List<IncidentQueries.IncidentSnapshot> active = incidents.overlapping(organizationId, now, now).stream()
                .filter(incident -> !incident.resolved())
                .sorted(java.util.Comparator.comparing(IncidentQueries.IncidentSnapshot::severity)
                        .thenComparing(IncidentQueries.IncidentSnapshot::openedAt, java.util.Comparator.reverseOrder()))
                .limit(Math.max(1, Math.min(limit, 20)))
                .toList();
        var summaries = repository.latestSummaries(organizationId,
                active.stream().map(IncidentQueries.IncidentSnapshot::id).toList());
        return active.stream().map(incident -> {
            BusinessImpactService.IncidentImpact impact = null;
            try {
                impact = business.estimate(organizationId, incident);
            } catch (RuntimeException e) {
                // The list must render even if one estimate fails; the incident page shows the details.
            }
            return new ActiveIncident(incident.id(), incident.title(), incident.severity(), incident.status(),
                    incident.affectedServices(), incident.openedAt(), summaries.get(incident.id()),
                    impact == null || impact.method().equals("SEM_KPI") ? null : impact.totalValue(),
                    impact == null ? null : impact.method());
        }).toList();
    }

    public AiStatus status() {
        boolean configured = properties.llmConfigured();
        return new AiStatus(properties.isEnabled(), configured, configured ? "AI" : "HEURISTIC",
                properties.isAutoInvestigate(), router.status(),
                properties.getRoutes().stream().map(AiProperties.Route::getModel).toList());
    }
    private void requireIncident(UUID organizationId, UUID incidentId) {
        if (incidents.find(organizationId, incidentId).isEmpty()) {
            throw new AiNotFoundException("Incidente não encontrado");
        }
    }
}
