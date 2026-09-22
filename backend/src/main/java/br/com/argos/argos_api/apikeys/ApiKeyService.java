package br.com.argos.argos_api.apikeys;

import br.com.argos.argos_api.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public GeneratedApiKey generateKey(String name) {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context found");
        }

        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String plainKey = "argos_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        
        String keyHash = hashKey(plainKey);

        ApiKey apiKey = new ApiKey();
        apiKey.setOrganizationId(UUID.fromString(tenantId));
        apiKey.setName(name);
        apiKey.setKeyHash(keyHash);
        
        apiKey = apiKeyRepository.save(apiKey);

        return new GeneratedApiKey(apiKey.getId(), apiKey.getName(), plainKey, apiKey.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<ApiKey> listKeys() {
        return apiKeyRepository.findAll();
    }

    @Transactional
    public void revokeKey(UUID id) {
        apiKeyRepository.findById(id).ifPresent(key -> {
            key.setRevoked(true);
            apiKeyRepository.save(key);
        });
    }

    @Transactional(readOnly = true)
    public Optional<UUID> resolveOrganizationId(String plainKey) {
        // This method can be called without a tenant context (e.g., from the ingestion pipeline)
        String hash = hashKey(plainKey);
        return apiKeyRepository.findByKeyHash(hash)
                .filter(key -> !key.isRevoked())
                .map(ApiKey::getOrganizationId);
    }

    private String hashKey(String plainKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(plainKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
