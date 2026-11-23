package br.com.argos.argos_api.shared.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Sets {@code app.current_tenant} on every connection taken from the pool, from the organization of the current
 * request or background task ({@link TenantAspect#resolveTenantId()}), or empty when there is none.
 *
 * <p>{@link TenantAspect} sets it before each repository call, but only inside the current transaction. Code that
 * uses {@code JdbcTemplate} directly (telemetry, business KPIs, DORA metrics) never goes through a repository, and
 * reads outside a transaction would lose a transaction-local setting before the query. Under row-level security
 * with a database user that is not a superuser (RDS in production), both would see and write nothing. The setting
 * here is session-wide and is replaced on every checkout, so a pooled connection never carries the previous
 * organization.
 */
class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_TENANT = "SELECT set_config('" + TenantSession.TENANT_SETTING + "', ?, false)";

    TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withTenant(super.getConnection(username, password));
    }

    private static Connection withTenant(Connection connection) throws SQLException {
        String tenant = TenantAspect.resolveTenantId();
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
            statement.setString(1, tenant == null ? "" : tenant);
            statement.execute();
            if (!connection.getAutoCommit()) {
                // A session setting made in a transaction that later rolls back would be undone with it.
                connection.commit();
            }
            return connection;
        } catch (SQLException | RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }
}
