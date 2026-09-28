package br.com.argos.argos_api.alerting;

import br.com.argos.argos_api.alerting.domain.AlertSource;
import br.com.argos.argos_api.alerting.domain.AlertSourceRepository;
import br.com.argos.argos_api.alerting.dto.AlertSourceRequest;
import br.com.argos.argos_api.alerting.dto.AlertSourceResponse;
import br.com.argos.argos_api.shared.security.AuthenticatedUser;
import br.com.argos.argos_api.shared.security.CurrentUser;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AlertSourceService {

    private static final Set<String> SEVERITIES = Set.of("SEV1", "SEV2", "SEV3", "SEV4");
    private static final Set<String> STATUSES = Set.of("firing", "resolved");

    private final AlertSourceRepository repository;
    private final CurrentUser currentUser;
    private final Clock clock;

    public AlertSourceService(AlertSourceRepository repository, CurrentUser currentUser, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public AlertSourceResponse create(AlertSourceRequest request) {
        AlertSourceRequest valid = validated(request);
        AuthenticatedUser user = currentUser.get();
        return AlertSourceResponse.from(repository.save(
                AlertSource.create(user.organizationId(), user.userId(), valid, now())));
    }

    @Transactional(readOnly = true)
    public List<AlertSourceResponse> list() {
        return repository.findByOrganizationIdAndDeletedAtIsNullOrderByName(currentUser.get().organizationId())
                .stream()
                .map(AlertSourceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AlertSourceResponse get(UUID id) {
        return AlertSourceResponse.from(findActive(currentUser.get().organizationId(), id));
    }

    @Transactional
    public AlertSourceResponse update(UUID id, AlertSourceRequest request) {
        AlertSourceRequest valid = validated(request);
        AlertSource source = findActive(currentUser.get().organizationId(), id);
        source.update(valid, now());
        return AlertSourceResponse.from(source);
    }

    @Transactional
    public void delete(UUID id) {
        findActive(currentUser.get().organizationId(), id).delete(now());
    }

    /**
     * Source of the given organization (taken from the API key on webhooks); removed sources do not count.
     */
    @Transactional(readOnly = true)
    public AlertSource findActive(UUID organizationId, UUID id) {
        return repository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, organizationId)
                .orElseThrow(() -> new AlertSourceNotFoundException(id));
    }

    /** Normalizes map values and rejects invalid paths or translations. */
    private static AlertSourceRequest validated(AlertSourceRequest request) {
        requirePath("titlePath", request.titlePath());
        requirePath("dedupKeyPath", request.dedupKeyPath());
        optionalPath("itemsPath", request.itemsPath());
        optionalPath("statusPath", request.statusPath());
        optionalPath("severityPath", request.severityPath());
        optionalPath("servicePath", request.servicePath());
        optionalPath("descriptionPath", request.descriptionPath());

        Map<String, String> severityMap = normalized("severityMap", request.severityMap(),
                value -> value.toUpperCase(Locale.ROOT), SEVERITIES);
        Map<String, String> statusMap = normalized("statusMap", request.statusMap(),
                value -> value.toLowerCase(Locale.ROOT), STATUSES);
        String defaultSeverity = request.defaultSeverity() == null || request.defaultSeverity().isBlank()
                ? "SEV3" : request.defaultSeverity().trim().toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(defaultSeverity)) {
            throw new IllegalArgumentException("defaultSeverity deve ser SEV1, SEV2, SEV3 ou SEV4");
        }
        return new AlertSourceRequest(request.name(), request.itemsPath(), request.titlePath(),
                request.dedupKeyPath(), request.statusPath(), request.severityPath(), request.servicePath(),
                request.descriptionPath(), statusMap, severityMap, defaultSeverity);
    }

    private static void requirePath(String field, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(field + " é obrigatório");
        }
        optionalPath(field, path);
    }

    private static void optionalPath(String field, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            JsonPath.compile(path.trim());
        } catch (InvalidPathException | IllegalArgumentException e) {
            throw new IllegalArgumentException("caminho inválido em " + field + ": " + path);
        }
    }

    private static Map<String, String> normalized(String field, Map<String, String> map,
                                                  java.util.function.UnaryOperator<String> normalize,
                                                  Set<String> allowed) {
        Map<String, String> result = new LinkedHashMap<>();
        if (map == null) {
            return result;
        }
        map.forEach((key, value) -> {
            String normalizedValue = value == null ? null : normalize.apply(value.trim());
            if (key == null || key.isBlank() || !allowed.contains(normalizedValue)) {
                throw new IllegalArgumentException(field + " aceita apenas os valores " + allowed);
            }
            result.put(key.trim(), normalizedValue);
        });
        return result;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
