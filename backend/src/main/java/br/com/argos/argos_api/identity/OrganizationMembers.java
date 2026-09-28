package br.com.argos.argos_api.identity;

import java.util.UUID;

/**
 * Public API of the identity module for other modules that need to check membership.
 */
public interface OrganizationMembers {

    boolean isMember(UUID organizationId, UUID userId);
}
