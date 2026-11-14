package br.com.argos.argos_api.shared.security;

import br.com.argos.argos_api.shared.tenant.TenantContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs background work (queues, schedulers, async listeners) as the system acting inside one organization.
 *
 * <p>Services that resolve the tenant through {@link CurrentUser} or {@code TenantContext} work unchanged, because
 * the thread gets a short-lived authentication carrying the organization as {@code tenant_id}. The previous context
 * is always restored, so pooled threads never leak a tenant into the next task.
 */
public final class SystemActor {

    /** Subject of the synthetic authentication; never a row in {@code users}. */
    public static final UUID SYSTEM_USER_ID = new UUID(0L, 0L);
    public static final String ROLE = "ROLE_SYSTEM";

    private SystemActor() {
    }

    public static <T> T callAs(UUID organizationId, Supplier<T> action) {
        SecurityContext previousContext = SecurityContextHolder.getContext();
        String previousTenant = TenantContext.getCurrentTenant();
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("system")
                .header("alg", "none")
                .subject(SYSTEM_USER_ID.toString())
                .claim("tenant_id", organizationId.toString())
                .claim("role", ROLE)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(ROLE))));
        SecurityContextHolder.setContext(context);
        TenantContext.setCurrentTenant(organizationId.toString());
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previousContext);
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentTenant(previousTenant);
            }
        }
    }

    public static void runAs(UUID organizationId, Runnable action) {
        callAs(organizationId, () -> {
            action.run();
            return null;
        });
    }
}
