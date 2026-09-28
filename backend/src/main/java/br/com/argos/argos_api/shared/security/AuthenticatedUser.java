package br.com.argos.argos_api.shared.security;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID organizationId) {}
