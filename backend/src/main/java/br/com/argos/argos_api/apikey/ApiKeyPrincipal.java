package br.com.argos.argos_api.apikey;

import java.util.UUID;


public record ApiKeyPrincipal(UUID keyId, UUID organizationId, String prefix) {
}
