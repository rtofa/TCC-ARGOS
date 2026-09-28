package br.com.argos.argos_api.shared.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CurrentUser {

    public AuthenticatedUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtToken)) {
            throw new AccessDeniedException("No authenticated user");
        }
        Jwt jwt = jwtToken.getToken();
        String tenantId = jwt.getClaimAsString("tenant_id");
        if (jwt.getSubject() == null || tenantId == null) {
            throw new AccessDeniedException("Token without subject or tenant");
        }
        return new AuthenticatedUser(UUID.fromString(jwt.getSubject()), UUID.fromString(tenantId));
    }
}
