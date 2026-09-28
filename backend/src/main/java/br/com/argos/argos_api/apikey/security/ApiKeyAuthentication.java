package br.com.argos.argos_api.apikey.security;

import br.com.argos.argos_api.apikey.ApiKeyPrincipal;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

class ApiKeyAuthentication extends AbstractAuthenticationToken {

    static final String INGEST_ROLE = "INGEST";

    private final ApiKeyPrincipal principal;

    ApiKeyAuthentication(ApiKeyPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + INGEST_ROLE)));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public ApiKeyPrincipal getPrincipal() {
        return principal;
    }
}
