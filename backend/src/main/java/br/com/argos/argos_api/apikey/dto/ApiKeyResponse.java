package br.com.argos.argos_api.apikey.dto;

import br.com.argos.argos_api.apikey.domain.ApiKey;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyResponse(
        UUID id,
        String name,
        String prefix,
        UUID createdBy,
        Instant createdAt,
        Instant lastUsedAt,
        String status,
        Instant revokedAt) {

    public static ApiKeyResponse from(ApiKey key) {
        return new ApiKeyResponse(key.getId(), key.getName(), key.getPrefix(), key.getCreatedBy(),
                key.getCreatedAt(), key.getLastUsedAt(), key.isActive() ? "ACTIVE" : "REVOKED", key.getRevokedAt());
    }
}
