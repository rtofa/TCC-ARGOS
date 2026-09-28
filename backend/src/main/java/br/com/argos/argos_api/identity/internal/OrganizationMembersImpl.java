package br.com.argos.argos_api.identity.internal;

import br.com.argos.argos_api.identity.OrganizationMembers;
import br.com.argos.argos_api.identity.UserRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
class OrganizationMembersImpl implements OrganizationMembers {

    private final UserRepository userRepository;

    OrganizationMembersImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean isMember(UUID organizationId, UUID userId) {
        if (organizationId == null || userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(user -> organizationId.equals(user.getOrganizationId()))
                .orElse(false);
    }
}
