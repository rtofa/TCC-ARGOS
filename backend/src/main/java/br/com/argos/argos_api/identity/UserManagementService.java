package br.com.argos.argos_api.identity;

import br.com.argos.argos_api.shared.security.AuthenticatedUser;
import br.com.argos.argos_api.shared.security.CurrentUser;
import br.com.argos.argos_api.shared.tenant.TenantSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class UserManagementService {

    public record UserSummary(UUID id, String name, String email, Role role, boolean active,
                              Instant lastLoginAt, LocalDateTime createdAt, boolean self) {
        static UserSummary from(User u, UUID currentUserId) {
            return new UserSummary(u.getId(), u.getName(), u.getEmail(), u.getRole(),
                    u.isActive(), u.getLastLoginAt(), u.getCreatedAt(), u.getId().equals(currentUserId));
        }
    }

    static class UserNotFoundException extends RuntimeException {
        UserNotFoundException(UUID id) {
            super("Usuário não encontrado: " + id);
        }
    }

    static class DuplicateEmailException extends RuntimeException {
        DuplicateEmailException(String email) {
            super("Já existe um usuário cadastrado com o e-mail " + email);
        }
    }

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUser currentUser;
    private final TenantSession tenantSession;

    public UserManagementService(UserRepository userRepository, PasswordEncoder passwordEncoder, CurrentUser currentUser,
                                 TenantSession tenantSession) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public List<UserSummary> list() {
        AuthenticatedUser actor = currentUser.get();
        return userRepository.findByOrganizationIdOrderByCreatedAtAsc(actor.organizationId()).stream()
                .map(u -> UserSummary.from(u, actor.userId()))
                .toList();
    }

    @Transactional
    public UserSummary create(String name, String rawEmail, String password, Role role) {
        AuthenticatedUser actor = currentUser.get();
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        // E-mails are unique across organizations, and row-level security would hide the other ones.
        if (tenantSession.lookup(() -> userRepository.findByEmailIgnoreCase(email)).isPresent()) {
            throw new DuplicateEmailException(email);
        }
        User user = new User();
        user.setOrganizationId(actor.organizationId());
        user.setName(name.trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role == null ? Role.VIEWER : role);
        user.setActive(true);
        return UserSummary.from(userRepository.save(user), actor.userId());
    }

    @Transactional
    public UserSummary changeRole(UUID id, Role newRole) {
        AuthenticatedUser actor = currentUser.get();
        User target = userRepository.findByIdAndOrganizationId(id, actor.organizationId())
                .orElseThrow(() -> new UserNotFoundException(id));

        if (target.getRole() == Role.ADMIN && newRole != Role.ADMIN && target.isActive()) {
            long activeAdmins = userRepository.countByOrganizationIdAndRoleAndActiveTrue(actor.organizationId(), Role.ADMIN);
            if (activeAdmins <= 1) {
                throw new IllegalArgumentException("A organização deve manter pelo menos um administrador ativo.");
            }
        }
        target.setRole(newRole);
        return UserSummary.from(userRepository.save(target), actor.userId());
    }

    @Transactional
    public UserSummary changeStatus(UUID id, boolean active) {
        AuthenticatedUser actor = currentUser.get();
        if (actor.userId().equals(id) && !active) {
            throw new IllegalArgumentException("Você não pode desativar o seu próprio acesso.");
        }
        User target = userRepository.findByIdAndOrganizationId(id, actor.organizationId())
                .orElseThrow(() -> new UserNotFoundException(id));
        if (!active && target.getRole() == Role.ADMIN && target.isActive()) {
            long activeAdmins = userRepository.countByOrganizationIdAndRoleAndActiveTrue(actor.organizationId(), Role.ADMIN);
            if (activeAdmins <= 1) {
                throw new IllegalArgumentException("A organização deve manter pelo menos um administrador ativo.");
            }
        }
        target.setActive(active);
        return UserSummary.from(userRepository.save(target), actor.userId());
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        AuthenticatedUser actor = currentUser.get();
        User target = userRepository.findByIdAndOrganizationId(id, actor.organizationId())
                .orElseThrow(() -> new UserNotFoundException(id));
        target.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(target);
    }
}
