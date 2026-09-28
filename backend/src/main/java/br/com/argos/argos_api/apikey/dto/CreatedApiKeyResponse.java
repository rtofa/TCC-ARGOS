package br.com.argos.argos_api.apikey.dto;

import java.time.Instant;
import java.util.UUID;


public record CreatedApiKeyResponse(UUID id, String name, String prefix, String key, Instant createdAt) {
}
