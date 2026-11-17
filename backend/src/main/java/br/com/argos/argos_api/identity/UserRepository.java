package br.com.argos.argos_api.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    List<User> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

    Optional<User> findByIdAndOrganizationId(UUID id, UUID organizationId);

    long countByOrganizationIdAndRoleAndActiveTrue(UUID organizationId, Role role);
}
