package br.com.argos.argos_api.delivery;

import br.com.argos.argos_api.delivery.domain.Deploy;
import br.com.argos.argos_api.delivery.domain.DeployRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Deploys in a time window, for other modules (the AI investigation correlates them with incidents). */
@Service
public class DeployHistory {

    private static final int MAX_RESULTS = 200;

    private final DeployRepository repository;

    public DeployHistory(DeployRepository repository) {
        this.repository = repository;
    }

    public record DeployEvent(String service, String version, String environment, String commitSha, String author,
                              String description, String url, Instant committedAt, Instant deployedAt) {
    }

    /**
     * Most recent first. An empty {@code services} collection means every service; names are matched ignoring case.
     */
    @Transactional(readOnly = true)
    public List<DeployEvent> between(UUID organizationId, Collection<String> services, Instant from, Instant to,
                                     int limit) {
        Set<String> wanted = services == null ? Set.of() : services.stream()
                .map(service -> service.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return repository.findTop200ByOrganizationIdAndDeployedAtBetweenOrderByDeployedAtDesc(organizationId, from, to)
                .stream()
                .filter(deploy -> wanted.isEmpty() || wanted.contains(deploy.getService().toLowerCase(Locale.ROOT)))
                .limit(Math.max(1, Math.min(limit, MAX_RESULTS)))
                .map(DeployHistory::event)
                .toList();
    }

    private static DeployEvent event(Deploy deploy) {
        return new DeployEvent(deploy.getService(), deploy.getVersion(), deploy.getEnvironment(), deploy.getCommitSha(),
                deploy.getAuthor(), deploy.getDescription(), deploy.getUrl(), deploy.getCommittedAt(),
                deploy.getDeployedAt());
    }
}
