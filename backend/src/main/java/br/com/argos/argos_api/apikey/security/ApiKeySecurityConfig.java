package br.com.argos.argos_api.apikey.security;

import br.com.argos.argos_api.apikey.ApiKeyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;


@Configuration
class ApiKeySecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain webhookSecurityFilterChain(HttpSecurity http, ApiKeyService apiKeyService) throws Exception {
        http
                .securityMatcher("/api/webhooks/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyService), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(ApiKeyAuthentication.INGEST_ROLE));
        return http.build();
    }
}
