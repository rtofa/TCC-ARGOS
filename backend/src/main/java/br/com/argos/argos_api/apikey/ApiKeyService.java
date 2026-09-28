package br.com.argos.argos_api.apikey;

import br.com.argos.argos_api.apikey.domain.ApiKey;
import br.com.argos.argos_api.apikey.domain.ApiKeyGenerator;
import br.com.argos.argos_api.apikey.domain.ApiKeyRepository;
import br.com.argos.argos_api.apikey.dto.ApiKeyResponse;
import br.com.argos.argos_api.apikey.dto.CreateApiKeyRequest;
import br.com.argos.argos_api.apikey.dto.CreatedApiKeyResponse;
import br.com.argos.argos_api.shared.security.AuthenticatedUser;
import br.com.argos.argos_api.shared.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApiKeyService {

    static final Duration LAST_USED_PRECISION = Duration.ofMinutes(1);

    private final ApiKeyRepository repository;
    private final CurrentUser currentUser;
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository repository, CurrentUser currentUser, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public CreatedApiKeyResponse create(CreateApiKeyRequest request) {
        AuthenticatedUser user = currentUser.get();
        String rawKey = ApiKeyGenerator.generate();
        ApiKey key = repository.save(ApiKey.create(user.organizationId(), request.name(),
                ApiKeyGenerator.prefixOf(rawKey), ApiKeyGenerator.hash(rawKey), user.userId(), now()));
        return new CreatedApiKeyResponse(key.getId(), key.getName(), key.getPrefix(), rawKey, key.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> list() {
        return repository.findByOrganizationIdOrderByCreatedAtDesc(currentUser.get().organizationId()).stream()
                .map(ApiKeyResponse::from)
                .toList();
    }

    @Transactional
    public ApiKeyResponse revoke(UUID id) {
        ApiKey key = repository.findByIdAndOrganizationId(id, currentUser.get().organizationId())
                .orElseThrow(() -> new ApiKeyNotFoundException(id));
        key.revoke(now());
        return ApiKeyResponse.from(key);
    }

    /**
     * Resolves a raw key sent by an external system; empty for unknown or revoked keys.
     */
    @Transactional
    public Optional<ApiKeyPrincipal> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        Optional<ApiKey> key = repository.findByKeyHash(ApiKeyGenerator.hash(rawKey)).filter(ApiKey::isActive);
        key.ifPresent(k -> {
            Instant now = now();
            repository.touchLastUsed(k.getId(), now, now.minus(LAST_USED_PRECISION));
        });
        return key.map(k -> new ApiKeyPrincipal(k.getId(), k.getOrganizationId(), k.getPrefix()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
