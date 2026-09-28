package br.com.argos.argos_api.incident;

import br.com.argos.argos_api.identity.OrganizationMembers;
import br.com.argos.argos_api.incident.domain.Incident;
import br.com.argos.argos_api.incident.domain.IncidentEvent;
import br.com.argos.argos_api.incident.domain.IncidentEventRepository;
import br.com.argos.argos_api.incident.domain.IncidentRepository;
import br.com.argos.argos_api.incident.domain.IncidentSpecifications;
import br.com.argos.argos_api.incident.domain.IncidentStatus;
import br.com.argos.argos_api.incident.domain.Severity;
import br.com.argos.argos_api.incident.dto.PageResponse;
import br.com.argos.argos_api.incident.dto.AssignIncidentRequest;
import br.com.argos.argos_api.incident.dto.CommentRequest;
import br.com.argos.argos_api.incident.dto.CreateIncidentRequest;
import br.com.argos.argos_api.incident.dto.IncidentEventResponse;
import br.com.argos.argos_api.incident.dto.IncidentMetricsResponse;
import br.com.argos.argos_api.incident.dto.IncidentMetricsResponse.MetricsBucket;
import br.com.argos.argos_api.incident.dto.IncidentMetricsResponse.SeverityMetricsBucket;
import br.com.argos.argos_api.incident.dto.IncidentResponse;
import br.com.argos.argos_api.incident.dto.UpdateIncidentRequest;
import br.com.argos.argos_api.shared.security.AuthenticatedUser;
import br.com.argos.argos_api.shared.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Incident use cases. Every lookup is scoped to the organization of the authenticated user,
 * so incidents of other tenants behave as nonexistent.
 */
@Service
public class IncidentService {

    static final int MAX_PAGE_SIZE = 100;
    static final Duration DEFAULT_METRICS_PERIOD = Duration.ofDays(30);

    private final IncidentRepository incidentRepository;
    private final IncidentEventRepository eventRepository;
    private final OrganizationMembers organizationMembers;
    private final CurrentUser currentUser;
    private final Clock clock;

    public IncidentService(IncidentRepository incidentRepository, IncidentEventRepository eventRepository,
                           OrganizationMembers organizationMembers, CurrentUser currentUser, Clock clock) {
        this.incidentRepository = incidentRepository;
        this.eventRepository = eventRepository;
        this.organizationMembers = organizationMembers;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public IncidentResponse create(CreateIncidentRequest request) {
        AuthenticatedUser user = currentUser.get();
        validateAssignee(user.organizationId(), request.assigneeId());
        Instant now = now();

        Incident incident = Incident.open(user.organizationId(), user.userId(), request.title(),
                request.description(), request.severity(), request.affectedServices(), request.assigneeId(), now);
        incident = incidentRepository.save(incident);
        eventRepository.save(IncidentEvent.opened(incident, user.userId(), now));
        return IncidentResponse.from(incident);
    }

    @Transactional(readOnly = true)
    public IncidentResponse get(UUID id) {
        return IncidentResponse.from(findInTenant(id, currentUser.get()));
    }

    @Transactional(readOnly = true)
    public PageResponse<IncidentResponse> list(Set<IncidentStatus> statuses, Set<Severity> severities,
                                               Instant openedFrom, Instant openedTo, int page, int size) {
        AuthenticatedUser user = currentUser.get();
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "openedAt"));
        Page<IncidentResponse> result = incidentRepository
                .findAll(IncidentSpecifications.matching(user.organizationId(), statuses, severities,
                        openedFrom, openedTo), pageRequest)
                .map(IncidentResponse::from);
        return PageResponse.from(result);
    }

    @Transactional
    public IncidentResponse update(UUID id, UpdateIncidentRequest request) {
        if (request.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos um campo para alterar");
        }
        AuthenticatedUser user = currentUser.get();
        Incident incident = findInTenant(id, user);
        Instant now = now();

        incident.edit(request.title(), request.description(), request.affectedServices(), now);
        List<IncidentEvent> events = new ArrayList<>();
        events.addAll(incident.changeSeverity(request.severity(), user.userId(), now));
        events.addAll(incident.changeStatus(request.status(), user.userId(), now));
        if (!events.isEmpty()) {
            eventRepository.saveAll(events);
        }
        return IncidentResponse.from(incident);
    }

    @Transactional
    public IncidentResponse assign(UUID id, AssignIncidentRequest request) {
        AuthenticatedUser user = currentUser.get();
        Incident incident = findInTenant(id, user);
        validateAssignee(user.organizationId(), request.assigneeId());

        List<IncidentEvent> events = incident.assign(request.assigneeId(), user.userId(), now());
        if (!events.isEmpty()) {
            eventRepository.saveAll(events);
        }
        return IncidentResponse.from(incident);
    }

    @Transactional(readOnly = true)
    public List<IncidentEventResponse> timeline(UUID id) {
        AuthenticatedUser user = currentUser.get();
        Incident incident = findInTenant(id, user);
        return eventRepository
                .findByIncidentIdAndOrganizationIdOrderByOccurredAtAscSeqAsc(incident.getId(), user.organizationId())
                .stream()
                .map(IncidentEventResponse::from)
                .toList();
    }

    @Transactional
    public IncidentEventResponse comment(UUID id, CommentRequest request) {
        AuthenticatedUser user = currentUser.get();
        Incident incident = findInTenant(id, user);
        Instant now = now();

        incident.touch(now);
        IncidentEvent event = eventRepository.save(IncidentEvent.comment(incident, user.userId(), now, request.body()));
        return IncidentEventResponse.from(event);
    }

    /**
     * MTTA/MTTR averages for incidents opened in [openedFrom, openedTo); defaults to the last 30 days.
     */
    @Transactional(readOnly = true)
    public IncidentMetricsResponse metrics(Instant openedFrom, Instant openedTo, String groupBy) {
        if (groupBy != null && !groupBy.equals("severity")) {
            throw new IllegalArgumentException("groupBy aceita apenas 'severity'");
        }
        Instant to = openedTo != null ? openedTo : now();
        Instant from = openedFrom != null ? openedFrom : to.minus(DEFAULT_METRICS_PERIOD);
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("openedTo deve ser posterior a openedFrom");
        }
        UUID organizationId = currentUser.get().organizationId();

        MetricsBucket overall = MetricsBucket.from(incidentRepository.aggregateMetrics(organizationId, from, to));
        List<SeverityMetricsBucket> bySeverity = groupBy == null ? null
                : incidentRepository.aggregateMetricsBySeverity(organizationId, from, to).stream()
                        .map(SeverityMetricsBucket::from)
                        .toList();
        return new IncidentMetricsResponse(from, to, overall, bySeverity);
    }

    /** PostgreSQL stores microseconds; truncating keeps responses equal to what is persisted. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private Incident findInTenant(UUID id, AuthenticatedUser user) {
        return incidentRepository.findByIdAndOrganizationId(id, user.organizationId())
                .orElseThrow(() -> new IncidentNotFoundException(id));
    }

    private void validateAssignee(UUID organizationId, UUID assigneeId) {
        if (assigneeId != null && !organizationMembers.isMember(organizationId, assigneeId)) {
            throw new InvalidAssigneeException(assigneeId);
        }
    }
}
