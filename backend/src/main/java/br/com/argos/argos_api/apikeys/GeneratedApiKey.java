package br.com.argos.argos_api.apikeys;

import java.time.LocalDateTime;
import java.util.UUID;

public record GeneratedApiKey(UUID id, String name, String plainKey, LocalDateTime createdAt) {}
