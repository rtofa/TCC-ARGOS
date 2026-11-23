package br.com.argos.argos_api.identity;

import br.com.argos.argos_api.shared.tenant.TenantSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final TenantSession tenantSession;

    public AuthService(UserRepository userRepository, OrganizationRepository organizationRepository,
                       PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder, TenantSession tenantSession) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.tenantSession = tenantSession;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // No organization yet: the e-mail must be unique across all of them (see TenantSession).
        if (tenantSession.lookup(() -> userRepository.findByEmailIgnoreCase(request.email())).isPresent()) {
            throw new IllegalArgumentException("Email already in use");
        }

        Organization org = new Organization();
        org.setName(request.organizationName());
        org = organizationRepository.save(org);

        User user = new User();
        user.setOrganizationId(org.getId());
        user.setName(request.userName());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.ADMIN);
        User saved = tenantSession.callAs(org.getId(), () -> userRepository.save(user));

        String token = generateToken(saved);
        return new AuthResponse(token);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
        User user = tenantSession.lookup(() -> userRepository.findByEmailIgnoreCase(email))
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Usuário desativado. Procure o administrador da organização.");
        }

        tenantSession.callAs(user.getOrganizationId(), () -> {
            user.setLastLoginAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
            return userRepository.save(user);
        });

        String token = generateToken(user);
        return new AuthResponse(token);
    }

    private String generateToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("self")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(user.getId().toString())
                .claim("tenant_id", user.getOrganizationId().toString())
                .claim("role", "ROLE_" + user.getRole().name())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
