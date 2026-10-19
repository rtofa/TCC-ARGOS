package br.com.argos.argos_api.delivery;

import br.com.argos.argos_api.apikey.ApiKeyPrincipal;
import br.com.argos.argos_api.delivery.domain.Deploy;
import br.com.argos.argos_api.delivery.domain.DeployRepository;
import br.com.argos.argos_api.delivery.domain.DeploySpecifications;
import br.com.argos.argos_api.delivery.dto.DeployPageResponse;
import br.com.argos.argos_api.delivery.dto.DeployResponse;
import br.com.argos.argos_api.delivery.dto.RecordDeployRequest;
import br.com.argos.argos_api.shared.security.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class DeployService {

    static final int MAX_PAGE_SIZE = 100;
    static final String DEFAULT_ENVIRONMENT = "production";
    /** Pipelines' clocks may run slightly ahead of ours. */
    static final Duration ALLOWED_CLOCK_SKEW = Duration.ofMinutes(5);

    private final DeployRepository repository;
    private final CurrentUser currentUser;
    private final Clock clock;

    public DeployService(DeployRepository repository, CurrentUser currentUser, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** The organization always comes from the API key, never from the body. */
    @Transactional
    public DeployResponse record(ApiKeyPrincipal key, RecordDeployRequest request) {
        Instant now = now();
        Instant deployedAt = request.deployedAt() == null ? now : request.deployedAt().truncatedTo(ChronoUnit.MICROS);
        if (deployedAt.isAfter(now.plus(ALLOWED_CLOCK_SKEW))) {
            throw new IllegalArgumentException("deployedAt não pode estar no futuro");
        }
        String environment = blankToNull(request.environment());
        String commitSha = blankToNull(request.commitSha());
        Deploy deploy = Deploy.record(key.organizationId(), key.keyId(), request.service().trim(),
                request.version().trim(),
                environment == null ? DEFAULT_ENVIRONMENT : environment.toLowerCase(Locale.ROOT),
                commitSha == null ? null : commitSha.toLowerCase(Locale.ROOT),
                blankToNull(request.author()), validUrl(request.url()), blankToNull(request.description()),
                deployedAt, now);
        return DeployResponse.from(repository.save(deploy));
    }

    /** Newest first. from is inclusive and to exclusive; blank filters are ignored. */
    @Transactional(readOnly = true)
    public DeployPageResponse list(Collection<String> services, String environment, Instant from, Instant to,
                                   int page, int size) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from deve ser anterior a to");
        }
        List<String> serviceFilter = services == null ? List.of()
                : services.stream().map(DeployService::blankToNull).filter(Objects::nonNull).distinct().toList();
        String environmentFilter = blankToNull(environment);
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "deployedAt"));
        return DeployPageResponse.from(repository
                .findAll(DeploySpecifications.matching(currentUser.get().organizationId(), serviceFilter,
                        environmentFilter == null ? null : environmentFilter.toLowerCase(Locale.ROOT), from, to),
                        pageRequest)
                .map(DeployResponse::from));
    }

    /** Only http(s): the link is rendered in the UI, so javascript: and friends must never be stored. */
    private static String validUrl(String url) {
        String value = blankToNull(url);
        if (value == null) {
            return null;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && uri.getHost() != null) {
                return value;
            }
        } catch (URISyntaxException e) {
            // Falls through to the error below.
        }
        throw new IllegalArgumentException("url deve ser um endereço http ou https");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
