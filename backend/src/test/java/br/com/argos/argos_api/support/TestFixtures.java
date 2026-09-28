package br.com.argos.argos_api.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Shared test data helpers. Organizations and users are inserted directly because most tables
 * reference them through foreign keys.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static UUID createOrganization(JdbcTemplate jdbc, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    public static UUID createUser(JdbcTemplate jdbc, UUID organizationId, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, organization_id, name, email, password_hash, role) VALUES (?, ?, ?, ?, ?, ?)",
                id, organizationId, role + " user", id + "@test.com", "x", role);
        return id;
    }

    public static RequestPostProcessor token(UUID userId, UUID organizationId, String role) {
        return jwt()
                .jwt(j -> j.subject(userId.toString()).claim("tenant_id", organizationId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /**
     * Deletes every row in foreign-key order. Tables created by later migrations are skipped
     * while they do not exist yet.
     */
    public static void cleanDatabase(JdbcTemplate jdbc) {
        for (String table : new String[]{"alert_incident_links", "alert_sources", "incident_events", "incidents",
                "api_keys", "users", "organizations"}) {
            Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
            if (Boolean.TRUE.equals(exists)) {
                jdbc.execute("DELETE FROM " + table);
            }
        }
    }
}
