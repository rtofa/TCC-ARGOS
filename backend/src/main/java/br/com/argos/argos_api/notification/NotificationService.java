package br.com.argos.argos_api.notification;

import br.com.argos.argos_api.incident.IncidentQueries;
import br.com.argos.argos_api.incident.IncidentQueries.IncidentSnapshot;
import br.com.argos.argos_api.notification.domain.NotificationChannel;
import br.com.argos.argos_api.notification.domain.NotificationChannelRepository;
import br.com.argos.argos_api.notification.domain.NotificationChannelType;
import br.com.argos.argos_api.notification.dto.NotificationChannelRequest;
import br.com.argos.argos_api.notification.dto.NotificationChannelResponse;
import br.com.argos.argos_api.notification.dto.NotificationTestResult;
import br.com.argos.argos_api.shared.security.AuthenticatedUser;
import br.com.argos.argos_api.shared.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final NotificationChannelRepository repository;
    private final NotificationDeliveryService delivery;
    private final IncidentQueries incidentQueries;
    private final CurrentUser currentUser;
    private final Clock clock;

    public NotificationService(NotificationChannelRepository repository,
                               NotificationDeliveryService delivery,
                               IncidentQueries incidentQueries,
                               CurrentUser currentUser,
                               Clock clock) {
        this.repository = repository;
        this.delivery = delivery;
        this.incidentQueries = incidentQueries;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<NotificationChannelResponse> list() {
        return repository.findByOrganizationIdOrderByCreatedAtDesc(currentUser.get().organizationId()).stream()
                .map(NotificationChannelResponse::from)
                .toList();
    }

    @Transactional
    public NotificationChannelResponse create(NotificationChannelRequest request) {
        AuthenticatedUser user = currentUser.get();
        String target = validateTarget(request.type(), request.target(), true);
        String minSeverity = normalizeSeverity(request.minSeverity());
        boolean enabled = request.enabled() == null || request.enabled();
        Instant now = now();

        NotificationChannel channel = NotificationChannel.create(
                user.organizationId(), user.userId(), request.name().trim(),
                request.type(), target, minSeverity, enabled, now);
        return NotificationChannelResponse.from(repository.save(channel));
    }

    @Transactional
    public NotificationChannelResponse update(UUID id, NotificationChannelRequest request) {
        AuthenticatedUser user = currentUser.get();
        NotificationChannel channel = repository.findByIdAndOrganizationId(id, user.organizationId())
                .orElseThrow(() -> new NotificationNotFoundException(id));

        String newTarget = request.target() != null && !request.target().isBlank()
                ? validateTarget(request.type(), request.target(), true)
                : null;
        String minSeverity = normalizeSeverity(request.minSeverity());
        boolean enabled = request.enabled() == null ? channel.isEnabled() : request.enabled();

        channel.update(request.name().trim(), request.type(), newTarget, minSeverity, enabled, now());
        return NotificationChannelResponse.from(repository.save(channel));
    }

    @Transactional
    public void delete(UUID id) {
        AuthenticatedUser user = currentUser.get();
        NotificationChannel channel = repository.findByIdAndOrganizationId(id, user.organizationId())
                .orElseThrow(() -> new NotificationNotFoundException(id));
        repository.delete(channel);
    }

    @Transactional
    public NotificationTestResult testChannel(UUID id) {
        AuthenticatedUser user = currentUser.get();
        NotificationChannel channel = repository.findByIdAndOrganizationId(id, user.organizationId())
                .orElseThrow(() -> new NotificationNotFoundException(id));
        Instant now = now();
        IncidentSnapshot sample = new IncidentSnapshot(
                UUID.randomUUID(), user.organizationId(),
                "Teste de notificação do Argos",
                "Mensagem de validação para confirmar entrega do canal '" + channel.getName() + "'.",
                channel.getMinSeverity(), "OPEN", "MANUAL",
                List.of("checkout-api", "payment"), now, null, null);
        try {
            delivery.deliver(channel, sample, true);
            channel.markDelivered(now);
            repository.save(channel);
            return new NotificationTestResult(true, "Notificação de teste entregue com sucesso.", now);
        } catch (Exception e) {
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            channel.markFailed(err);
            repository.save(channel);
            return new NotificationTestResult(false, "Falha ao entregar: " + err, now);
        }
    }

    /**
     * Chamado em contexto de tenant ({@code SystemActor}) após commit da abertura de um incidente.
     */
    @Transactional
    public void notifyIncidentOpened(UUID organizationId, UUID incidentId) {
        Optional<IncidentSnapshot> maybeIncident = incidentQueries.find(organizationId, incidentId);
        if (maybeIncident.isEmpty()) {
            return;
        }
        IncidentSnapshot incident = maybeIncident.get();
        List<NotificationChannel> channels = repository.findByOrganizationIdAndEnabledTrue(organizationId);
        if (channels.isEmpty()) {
            return;
        }
        Instant now = now();
        for (NotificationChannel channel : channels) {
            if (!matchesSeverity(incident.severity(), channel.getMinSeverity())) {
                continue;
            }
            try {
                delivery.deliver(channel, incident, false);
                channel.markDelivered(now);
            } catch (Exception e) {
                log.warn("Falha ao enviar notificação para canal {} ({}) da org {}: {}",
                        channel.getName(), channel.getType(), organizationId, e.getMessage());
                channel.markFailed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            repository.save(channel);
        }
    }

    static boolean matchesSeverity(String incidentSeverity, String minSeverity) {
        return severityRank(incidentSeverity) <= severityRank(minSeverity);
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "SEV4" : severity.toUpperCase(Locale.ROOT)) {
            case "SEV1" -> 1;
            case "SEV2" -> 2;
            case "SEV3" -> 3;
            default -> 4;
        };
    }

    private static String normalizeSeverity(String minSeverity) {
        return minSeverity == null || minSeverity.isBlank() ? "SEV3" : minSeverity.toUpperCase(Locale.ROOT);
    }

    private static String validateTarget(NotificationChannelType type, String rawTarget, boolean required) {
        if (rawTarget == null || rawTarget.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("target é obrigatório");
            }
            return null;
        }
        String trimmed = rawTarget.trim();
        if (type == NotificationChannelType.EMAIL) {
            for (String part : trimmed.split(",")) {
                String addr = part.trim();
                if (addr.isEmpty() || !EMAIL_PATTERN.matcher(addr).matches()) {
                    throw new IllegalArgumentException("E-mail de destino inválido: " + addr);
                }
            }
            return trimmed;
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                throw new IllegalArgumentException("URL de webhook deve começar com http:// ou https://");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("URL de webhook deve possuir um host válido");
            }
            return trimmed;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL de webhook inválida: " + e.getMessage());
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
