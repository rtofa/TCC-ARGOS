package br.com.argos.argos_api.apikey;

import br.com.argos.argos_api.apikey.domain.ApiKey;
import br.com.argos.argos_api.apikey.domain.ApiKeyGenerator;
import br.com.argos.argos_api.apikey.domain.ApiKeyRepository;
import br.com.argos.argos_api.apikey.dto.ApiKeyResponse;
import br.com.argos.argos_api.apikey.dto.CreateApiKeyRequest;
import br.com.argos.argos_api.apikey.dto.CreatedApiKeyResponse;
import br.com.argos.argos_api.shared.security.AuthenticatedUser;
import br.com.argos.argos_api.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    @Mock
    private ApiKeyRepository repository;
    @Mock
    private CurrentUser currentUser;

    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(repository, currentUser, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(currentUser.get()).thenReturn(new AuthenticatedUser(ADMIN, ORG));
    }

    @Test
    void createStoresOnlyHashAndPrefixAndReturnsKeyOnce() {
        when(repository.save(any(ApiKey.class))).then(returnsFirstArg());

        CreatedApiKeyResponse response = service.create(new CreateApiKeyRequest("Grafana produção"));

        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(saved.capture());
        ApiKey key = saved.getValue();
        assertThat(response.key()).startsWith("argos_");
        assertThat(key.getKeyHash()).isEqualTo(ApiKeyGenerator.hash(response.key())).isNotEqualTo(response.key());
        assertThat(key.getPrefix()).isEqualTo(response.key().substring(0, 12)).isEqualTo(response.prefix());
        assertThat(key.getOrganizationId()).isEqualTo(ORG);
        assertThat(key.getCreatedBy()).isEqualTo(ADMIN);
        assertThat(key.getCreatedAt()).isEqualTo(NOW);
        assertThat(response.name()).isEqualTo("Grafana produção");
    }

    @Test
    void listReturnsOnlyTheOrganizationKeysWithStatus() {
        ApiKey active = ApiKey.create(ORG, "a", "argos_aaaaaa", "h1", ADMIN, NOW);
        ApiKey revoked = ApiKey.create(ORG, "b", "argos_bbbbbb", "h2", ADMIN, NOW);
        revoked.revoke(NOW);
        when(repository.findByOrganizationIdOrderByCreatedAtDesc(ORG)).thenReturn(List.of(active, revoked));

        List<ApiKeyResponse> keys = service.list();

        assertThat(keys).extracting(ApiKeyResponse::status).containsExactly("ACTIVE", "REVOKED");
    }

    @Test
    void revokeIsPermanentAndIdempotent() {
        ApiKey key = ApiKey.create(ORG, "a", "argos_aaaaaa", "h1", ADMIN, NOW.minusSeconds(60));
        key.revoke(NOW.minusSeconds(30));
        when(repository.findByIdAndOrganizationId(key.getId(), ORG)).thenReturn(Optional.of(key));

        ApiKeyResponse response = service.revoke(key.getId());

        assertThat(response.status()).isEqualTo("REVOKED");
        assertThat(response.revokedAt()).isEqualTo(NOW.minusSeconds(30));
    }

    @Test
    void revokeOfAnotherOrganizationKeyIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(id)).isInstanceOf(ApiKeyNotFoundException.class);
    }

    @Test
    void authenticatesActiveKeyAndTouchesLastUse() {
        String raw = ApiKeyGenerator.generate();
        ApiKey key = ApiKey.create(ORG, "a", ApiKeyGenerator.prefixOf(raw), ApiKeyGenerator.hash(raw), ADMIN, NOW);
        when(repository.findByKeyHash(ApiKeyGenerator.hash(raw))).thenReturn(Optional.of(key));

        Optional<ApiKeyPrincipal> principal = service.authenticate(raw);

        assertThat(principal).contains(new ApiKeyPrincipal(key.getId(), ORG, key.getPrefix()));
        verify(repository).touchLastUsed(key.getId(), NOW, NOW.minusSeconds(60));
    }

    @Test
    void rejectsUnknownAndRevokedKeys() {
        String raw = ApiKeyGenerator.generate();
        ApiKey revoked = ApiKey.create(ORG, "a", ApiKeyGenerator.prefixOf(raw), ApiKeyGenerator.hash(raw), ADMIN, NOW);
        revoked.revoke(NOW);
        when(repository.findByKeyHash(ApiKeyGenerator.hash(raw))).thenReturn(Optional.of(revoked));
        when(repository.findByKeyHash(ApiKeyGenerator.hash("argos_unknown"))).thenReturn(Optional.empty());

        assertThat(service.authenticate(raw)).isEmpty();
        assertThat(service.authenticate("argos_unknown")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
        verify(repository, never()).touchLastUsed(any(), any(), any());
    }
}
