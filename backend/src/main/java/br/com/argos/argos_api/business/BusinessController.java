package br.com.argos.argos_api.business;

import br.com.argos.argos_api.business.BusinessImpactService.ExecutiveSummary;
import br.com.argos.argos_api.business.BusinessImpactService.IncidentImpact;
import br.com.argos.argos_api.business.BusinessKpis.Kpi;
import br.com.argos.argos_api.business.BusinessKpis.ServiceWeight;
import br.com.argos.argos_api.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
class BusinessController {

    record KpiRequest(@NotBlank @Size(max = 80) String name, @Size(max = 300) String description,
                      @NotBlank @Size(max = 30) String unit,
                      @NotNull @DecimalMin("0") BigDecimal baselinePerMinute,
                      @DecimalMin("0") BigDecimal valuePerUnit,
                      @Size(max = 50) List<@Valid ServiceWeightRequest> services) {
    }

    record ServiceWeightRequest(@NotBlank @Size(max = 200) String service,
                                @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal impactPercent) {
    }

    private final BusinessKpis kpis;
    private final BusinessImpactService impacts;
    private final CurrentUser currentUser;
    private final Clock clock;

    BusinessController(BusinessKpis kpis, BusinessImpactService impacts, CurrentUser currentUser, Clock clock) {
        this.kpis = kpis;
        this.impacts = impacts;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping("/api/business/kpis")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    List<Kpi> list() {
        return kpis.list(org());
    }

    @PostMapping("/api/business/kpis")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    @Transactional
    ResponseEntity<Kpi> create(@Valid @RequestBody KpiRequest request) {
        UUID org = org();
        UUID id = kpis.create(org, request.name().trim(), blankToNull(request.description()), request.unit().trim(),
                request.baselinePerMinute(), request.valuePerUnit());
        kpis.replaceServices(org, id, weights(request));
        impacts.invalidate();
        return ResponseEntity.status(HttpStatus.CREATED).body(kpis.find(org, id).orElseThrow());
    }

    @PutMapping("/api/business/kpis/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    @Transactional
    Kpi update(@PathVariable UUID id, @Valid @RequestBody KpiRequest request) {
        UUID org = org();
        if (!kpis.update(org, id, request.name().trim(), blankToNull(request.description()), request.unit().trim(),
                request.baselinePerMinute(), request.valuePerUnit())) {
            throw new BusinessNotFoundException("KPI não encontrado");
        }
        kpis.replaceServices(org, id, weights(request));
        impacts.invalidate();
        return kpis.find(org, id).orElseThrow();
    }

    @DeleteMapping("/api/business/kpis/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!kpis.delete(org(), id)) {
            throw new BusinessNotFoundException("KPI não encontrado");
        }
        impacts.invalidate();
        return ResponseEntity.noContent().build();
    }

    /** NEG-03: estimated business cost of the incident (keeps growing while it is open). */
    @GetMapping("/api/incidents/{id}/impact")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    IncidentImpact impact(@PathVariable UUID id) {
        return impacts.forIncident(org(), id).orElseThrow(() -> new BusinessNotFoundException("Incidente não encontrado"));
    }

    /** Console home: active incidents with their estimated business cost. */
    @GetMapping("/api/business/active-incidents")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    List<BusinessImpactService.ActiveIncident> activeIncidents(@RequestParam(defaultValue = "8") int limit) {
        return impacts.activeIncidents(org(), limit);
    }

    /** NEG-04: executive view of the last {@code days} days. */
    @GetMapping("/api/business/executive")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    ExecutiveSummary executive(@RequestParam(defaultValue = "30") int days) {
        if (days < 1 || days > 365) {
            throw new IllegalArgumentException("days deve estar entre 1 e 365");
        }
        Instant to = clock.instant();
        return impacts.executive(org(), to.minus(Duration.ofDays(days)), to);
    }

    private UUID org() {
        return currentUser.get().organizationId();
    }

    /** Same service twice keeps the last weight; names keep the case the user typed. */
    private static List<ServiceWeight> weights(KpiRequest request) {
        Map<String, ServiceWeight> unique = new LinkedHashMap<>();
        if (request.services() != null) {
            request.services().forEach(weight -> unique.put(weight.service().trim().toLowerCase(Locale.ROOT),
                    new ServiceWeight(null, weight.service().trim(), weight.impactPercent())));
        }
        return List.copyOf(unique.values());
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
