package br.com.argos.argos_api.apikeys;

import java.time.LocalDateTime;
import java.util.UUID;

public record ApiKeyResponse(UUID id, String name, boolean revoked, LocalDateTime createdAt) {
    public static ApiKeyResponse fromEntity(ApiKey key) {
        return new ApiKeyResponse(key.getId(), key.getName(), key.isRevoked(), key.getCreatedAt());
    }
}
