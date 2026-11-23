package br.com.argos.argos_api;

import br.com.argos.argos_api.apikey.ApiKeyPrincipal;
import br.com.argos.argos_api.apikey.ApiKeyService;
import br.com.argos.argos_api.business.BusinessKpis;
import br.com.argos.argos_api.identity.AuthResponse;
import br.com.argos.argos_api.identity.AuthService;
import br.com.argos.argos_api.identity.LoginRequest;
import br.com.argos.argos_api.identity.RegisterRequest;
import br.com.argos.argos_api.shared.tenant.TenantContext;
import br.com.argos.argos_api.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The application under row-level security as it runs in production.
 *
 * <p>The tests (and local development) connect as a PostgreSQL superuser, which ignores row-level security. The RDS
 * master user is not a superuser, so there every table with {@code FORCE ROW LEVEL SECURITY} only shows and accepts
 * rows of {@code app.current_tenant}. Each test here switches to {@code argos_tenant} (no superuser, no BYPASSRLS)
 * inside a transaction and runs the real services: sign-up, login and API keys happen before the organization is
 * known, and JdbcTemplate code never goes through the repositories that set it.
 */
class RowLevelSecurityAsAppUserIT extends AbstractIntegrationTest {

    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private AuthService authService;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private BusinessKpis businessKpis;
    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void signUpCreatesTheOrganizationAndItsAdministrator() {
        AuthResponse response = asAppUser(() ->
                authService.register(new RegisterRequest("Loja Nova", "Ana", "ana@loja-nova.test", "senha-forte")));

        assertThat(response.token()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = 'ana@loja-nova.test'", String.class))
                .isEqualTo("ADMIN");
    }

    @Test
    void signUpRejectsAnEmailAlreadyUsedInAnotherOrganization() {
        String emailOfOrgB = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, adminB);

        assertThatThrownBy(() -> asAppUser(() ->
                authService.register(new RegisterRequest("Outra", "X", emailOfOrgB.toUpperCase(), "senha-forte"))))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loginFindsTheUserRecordsTheLastLoginAndLeavesNothingVisibleAfterwards() {
        authService.register(new RegisterRequest("Loja Nova", "Ana", "ana@loja-nova.test", "senha-forte"));

        Long visibleAfterLogin = asAppUser(() -> {
            AuthResponse response = authService.login(new LoginRequest(" ANA@loja-nova.test ", "senha-forte"));
            assertThat(response.token()).isNotBlank();
            // Neither the lookup permission nor the user's organization may outlive the login.
            return jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        });

        assertThat(visibleAfterLogin).isZero();
        assertThat(jdbc.queryForObject("SELECT last_login_at FROM users WHERE email = 'ana@loja-nova.test'",
                Timestamp.class)).isNotNull();
    }

    @Test
    void apiKeyAuthenticationResolvesTheOrganizationAndRecordsTheLastUse() throws Exception {
        String created = mockMvc.perform(post("/api/api-keys").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Collector\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String rawKey = JsonPath.read(created, "$.key");

        Optional<ApiKeyPrincipal> principal = asAppUser(() -> apiKeyService.authenticate(rawKey));

        assertThat(principal).map(ApiKeyPrincipal::organizationId).contains(orgA);
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM api_keys WHERE key_hash IS NOT NULL", Timestamp.class))
                .isNotNull();
    }

    @Test
    void jdbcTemplateCodeWritesAndReadsWithTheOrganizationOfTheConnection() {
        TenantContext.setCurrentTenant(orgA.toString());

        int visible = asAppUser(() -> {
            businessKpis.create(orgA, "Pedidos", null, "pedidos", BigDecimal.TEN, BigDecimal.ONE);
            return businessKpis.list(orgA).size();
        });

        assertThat(visible).isEqualTo(1);
    }

    @Test
    void everyPooledConnectionCarriesTheOrganizationOfTheCurrentWork() throws Exception {
        TenantContext.setCurrentTenant(orgA.toString());
        assertThat(tenantOfNewConnection()).isEqualTo(orgA.toString());

        TenantContext.clear();
        assertThat(tenantOfNewConnection()).isEmpty();
    }

    /** Runs the work in a transaction as {@code argos_tenant}, a database user subject to row-level security. */
    private <T> T asAppUser(Supplier<T> work) {
        return transactionTemplate.execute(status -> {
            jdbc.execute("SET LOCAL ROLE argos_tenant");
            return work.get();
        });
    }

    private String tenantOfNewConnection() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT current_setting('app.current_tenant', true)")) {
            result.next();
            return result.getString(1);
        }
    }
}
