package br.com.argos.argos_api.business;

import br.com.argos.argos_api.business.BusinessKpis.Link;
import br.com.argos.argos_api.incident.IncidentQueries;
import br.com.argos.argos_api.incident.IncidentQueries.IncidentSnapshot;
import br.com.argos.argos_api.telemetry.TelemetryQueryService;
import br.com.argos.argos_api.telemetry.TelemetryViews.RedSeries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * NEG-03 and NEG-04: estimates what an incident costs the business, and aggregates it for the executive view.
 *
 * <p>For each KPI linked to an affected service: {@code lost = baseline/min × weight × degradation × minutes}. The
 * degradation is the service's real error rate during the incident when telemetry has enough requests, otherwise a
 * factor of the severity. Several services hitting the same KPI are not double counted: the worst one wins.
 */
@Service
public class BusinessImpactService {

    private static final Logger log = LoggerFactory.getLogger(BusinessImpactService.class);
    static final Map<String, BigDecimal> SEVERITY_DEGRADATION = Map.of(
            "SEV1", BigDecimal.ONE, "SEV2", new BigDecimal("0.50"), "SEV3", new BigDecimal("0.20"), "SEV4", new BigDecimal("0.05"));
    static final long MIN_REQUESTS_FOR_TELEMETRY = 20;
    static final Duration DETECTION_LEAD = Duration.ofMinutes(10);
    static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");

    public record KpiImpact(UUID kpiId, String kpi, String unit, BigDecimal degradationPercent, BigDecimal lostUnits,
                            BigDecimal lostValue, String service) {
    }

    /**
     * @param method "TELEMETRIA" when real error rates were used, "SEVERIDADE" when estimated from the severity,
     *               "SEM_KPI" when no affected service is linked to a KPI
     */
    public record IncidentImpact(UUID incidentId, boolean ongoing, long minutes, String method, List<KpiImpact> kpis,
                                 BigDecimal totalValue, String currency) {

        IncidentImpact scaled(long clippedMinutes) {
            if (minutes <= 0 || clippedMinutes >= minutes) {
                return this;
            }
            BigDecimal factor = BigDecimal.valueOf(clippedMinutes).divide(BigDecimal.valueOf(minutes), 6, RoundingMode.HALF_UP);
            return new IncidentImpact(incidentId, ongoing, clippedMinutes, method, kpis.stream()
                    .map(k -> new KpiImpact(k.kpiId(), k.kpi(), k.unit(), k.degradationPercent(),
                            k.lostUnits().multiply(factor).setScale(1, RoundingMode.HALF_UP),
                            k.lostValue() == null ? null : k.lostValue().multiply(factor).setScale(2, RoundingMode.HALF_UP),
                            k.service()))
                    .toList(), totalValue.multiply(factor).setScale(2, RoundingMode.HALF_UP), currency);
        }
    }

    public record IncidentCost(UUID incidentId, String title, String severity, Instant openedAt, boolean ongoing,
                               long minutes, BigDecimal value, String method) {
    }

    public record DayLoss(LocalDate day, BigDecimal value, long incidents) {
    }

    public record KpiLoss(String kpi, String unit, BigDecimal lostUnits, BigDecimal lostValue) {
    }

    public record ServiceLoss(String service, BigDecimal value, long incidents) {
    }

    public record ExecutiveSummary(Instant from, Instant to, String currency, BigDecimal totalLoss, long incidents,
                                   long incidentsWithImpact, long criticalIncidents, Long mttrSeconds,
                                   long impactedMinutes, boolean kpisConfigured, List<DayLoss> lossByDay,
                                   List<KpiLoss> lossByKpi, List<ServiceLoss> lossByService,
                                   List<IncidentCost> topIncidents) {
    }

    private final BusinessKpis kpis;
    private final IncidentQueries incidents;
    private final TelemetryQueryService telemetry;
    private final Clock clock;
    /** Resolved incidents never change cost unless the KPI setup changes, which clears this cache. */
    private final Map<UUID, IncidentImpact> resolvedCache = new ConcurrentHashMap<>();

    public BusinessImpactService(BusinessKpis kpis, IncidentQueries incidents, TelemetryQueryService telemetry, Clock clock) {
        this.kpis = kpis;
        this.incidents = incidents;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public Optional<IncidentImpact> forIncident(UUID organizationId, UUID incidentId) {
        return incidents.find(organizationId, incidentId).map(incident -> estimate(organizationId, incident));
    }

    /** Must run in the organization's security scope (request thread or {@code SystemActor}). */
    public IncidentImpact estimate(UUID organizationId, IncidentSnapshot incident) {
        if (incident.resolved()) {
            IncidentImpact cached = resolvedCache.get(incident.id());
            if (cached != null) {
                return cached;
            }
        }
        IncidentImpact impact = compute(organizationId, incident);
        if (incident.resolved()) {
            resolvedCache.put(incident.id(), impact);
        }
        return impact;
    }

    void invalidate() {
        resolvedCache.clear();
    }

    private IncidentImpact compute(UUID organizationId, IncidentSnapshot incident) {
        Instant end = incident.resolved() ? incident.resolvedAt() : clock.instant();
        long minutes = Math.max(0, Duration.between(incident.openedAt(), end).toMinutes());
        List<Link> links = kpis.links(organizationId, incident.affectedServices());
        if (links.isEmpty()) {
            return new IncidentImpact(incident.id(), !incident.resolved(), minutes, "SEM_KPI", List.of(), BigDecimal.ZERO, "BRL");
        }
        Map<String, BigDecimal> degradation = new LinkedHashMap<>();
        boolean measured = false;
        for (String service : links.stream().map(Link::service).distinct().toList()) {
            Optional<BigDecimal> observed = observedErrorRate(service, incident.openedAt().minus(DETECTION_LEAD), end);
            measured |= observed.isPresent();
            degradation.put(service, observed.orElse(SEVERITY_DEGRADATION.getOrDefault(incident.severity(), BigDecimal.ONE)));
        }
        Map<UUID, KpiImpact> worstPerKpi = new LinkedHashMap<>();
        for (Link link : links) {
            BigDecimal effective = link.impactPercent().movePointLeft(2).multiply(degradation.get(link.service()));
            BigDecimal lostUnits = link.baselinePerMinute().multiply(effective).multiply(BigDecimal.valueOf(minutes))
                    .setScale(1, RoundingMode.HALF_UP);
            BigDecimal lostValue = link.valuePerUnit() == null ? null
                    : lostUnits.multiply(link.valuePerUnit()).setScale(2, RoundingMode.HALF_UP);
            KpiImpact candidate = new KpiImpact(link.kpiId(), link.kpiName(), link.unit(),
                    effective.movePointRight(2).setScale(1, RoundingMode.HALF_UP), lostUnits, lostValue, link.service());
            worstPerKpi.merge(link.kpiId(), candidate,
                    (a, b) -> a.lostUnits().compareTo(b.lostUnits()) >= 0 ? a : b);
        }
        List<KpiImpact> impacts = List.copyOf(worstPerKpi.values());
        BigDecimal total = impacts.stream().map(KpiImpact::lostValue).filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new IncidentImpact(incident.id(), !incident.resolved(), minutes, measured ? "TELEMETRIA" : "SEVERIDADE",
                impacts, total, "BRL");
    }

    private Optional<BigDecimal> observedErrorRate(String service, Instant from, Instant to) {
        try {
            RedSeries red = telemetry.red(service, from, to.isAfter(from) ? to : from.plusSeconds(60));
            if (red.totalRequests() < MIN_REQUESTS_FOR_TELEMETRY) {
                return Optional.empty();
            }
            return Optional.of(BigDecimal.valueOf(red.errorRatePercent()).movePointLeft(2).setScale(4, RoundingMode.HALF_UP));
        } catch (RuntimeException e) {
            log.debug("Telemetria indisponível para {}: {}", service, e.getMessage());
            return Optional.empty();
        }
    }

    /** One active incident on the console home with what it is costing ({@code impactValue} null without KPIs). */
    public record ActiveIncident(UUID id, String title, String severity, String status, List<String> services,
                                 Instant openedAt, BigDecimal impactValue, String impactMethod) {
    }

    /** The most severe active incidents (newest first within a severity), at most {@code limit}. */
    public List<ActiveIncident> activeIncidents(UUID organizationId, int limit) {
        Instant now = clock.instant();
        return incidents.overlapping(organizationId, now, now).stream()
                .filter(incident -> !incident.resolved())
                .sorted(Comparator.comparing(IncidentSnapshot::severity)
                        .thenComparing(IncidentSnapshot::openedAt, Comparator.reverseOrder()))
                .limit(Math.max(1, Math.min(limit, 20)))
                .map(incident -> {
                    IncidentImpact impact = null;
                    try {
                        impact = estimate(organizationId, incident);
                    } catch (RuntimeException e) {
                        log.debug("Impacto indisponível para {}: {}", incident.id(), e.getMessage());
                    }
                    boolean priced = impact != null && !impact.method().equals("SEM_KPI");
                    return new ActiveIncident(incident.id(), incident.title(), incident.severity(), incident.status(),
                            incident.affectedServices(), incident.openedAt(), priced ? impact.totalValue() : null,
                            impact == null ? null : impact.method());
                })
                .toList();
    }

    /** NEG-04: cost of the incidents that overlap the window (clipped to it), with breakdowns for the dashboard. */
    public ExecutiveSummary executive(UUID organizationId, Instant from, Instant to) {
        List<IncidentSnapshot> overlapping = incidents.overlapping(organizationId, from, to);
        boolean configured = !kpis.links(organizationId, null).isEmpty();
        List<IncidentCost> costs = new ArrayList<>();
        Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
        Map<LocalDate, Long> incidentsByDay = new TreeMap<>();
        Map<String, BigDecimal[]> byKpi = new LinkedHashMap<>();
        Map<String, String> kpiUnits = new LinkedHashMap<>();
        Map<String, BigDecimal> byService = new LinkedHashMap<>();
        Map<String, Long> incidentsByService = new LinkedHashMap<>();
        long impactedMinutes = 0;
        long critical = 0;
        long resolvedCount = 0;
        long resolvedSeconds = 0;

        for (LocalDate day = LocalDate.ofInstant(from, BUSINESS_ZONE); !day.isAfter(LocalDate.ofInstant(to, BUSINESS_ZONE));
             day = day.plusDays(1)) {
            byDay.put(day, BigDecimal.ZERO);
            incidentsByDay.put(day, 0L);
        }

        for (IncidentSnapshot incident : overlapping) {
            Instant start = incident.openedAt().isBefore(from) ? from : incident.openedAt();
            Instant end = incident.resolved() ? incident.resolvedAt() : clock.instant();
            if (end.isAfter(to)) {
                end = to;
            }
            long clipped = Math.max(0, Duration.between(start, end).toMinutes());
            IncidentImpact impact = estimate(organizationId, incident).scaled(clipped);
            costs.add(new IncidentCost(incident.id(), incident.title(), incident.severity(), incident.openedAt(),
                    !incident.resolved(), clipped, impact.totalValue(), impact.method()));
            if (incident.severity().equals("SEV1") || incident.severity().equals("SEV2")) {
                critical++;
                impactedMinutes += clipped;
            }
            if (incident.resolved() && !incident.openedAt().isBefore(from)) {
                resolvedCount++;
                resolvedSeconds += Duration.between(incident.openedAt(), incident.resolvedAt()).toSeconds();
            }
            LocalDate day = LocalDate.ofInstant(start, BUSINESS_ZONE);
            byDay.merge(day, impact.totalValue(), BigDecimal::add);
            incidentsByDay.merge(day, 1L, Long::sum);
            for (KpiImpact kpi : impact.kpis()) {
                kpiUnits.putIfAbsent(kpi.kpi(), kpi.unit());
                byKpi.merge(kpi.kpi(), new BigDecimal[]{kpi.lostUnits(), kpi.lostValue() == null ? BigDecimal.ZERO : kpi.lostValue()},
                        (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
                byService.merge(kpi.service(), kpi.lostValue() == null ? BigDecimal.ZERO : kpi.lostValue(), BigDecimal::add);
            }
            impact.kpis().stream().map(KpiImpact::service).distinct()
                    .forEach(service -> incidentsByService.merge(service, 1L, Long::sum));
        }

        BigDecimal total = costs.stream().map(IncidentCost::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ExecutiveSummary(from, to, "BRL", total, overlapping.size(),
                costs.stream().filter(cost -> cost.value().signum() > 0).count(), critical,
                resolvedCount == 0 ? null : resolvedSeconds / resolvedCount, impactedMinutes, configured,
                byDay.entrySet().stream().map(e -> new DayLoss(e.getKey(), e.getValue(), incidentsByDay.get(e.getKey()))).toList(),
                byKpi.entrySet().stream()
                        .map(e -> new KpiLoss(e.getKey(), kpiUnits.get(e.getKey()), e.getValue()[0], e.getValue()[1]))
                        .sorted(Comparator.comparing(KpiLoss::lostValue).reversed()).toList(),
                byService.entrySet().stream()
                        .map(e -> new ServiceLoss(e.getKey(), e.getValue(), incidentsByService.getOrDefault(e.getKey(), 0L)))
                        .sorted(Comparator.comparing(ServiceLoss::value).reversed()).limit(8).toList(),
                costs.stream().sorted(Comparator.comparing(IncidentCost::value).reversed()
                        .thenComparing(IncidentCost::openedAt, Comparator.reverseOrder())).limit(5)
                        .collect(Collectors.toList()));
    }
}
