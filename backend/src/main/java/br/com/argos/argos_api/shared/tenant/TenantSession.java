package br.com.argos.argos_api.shared.tenant;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Changes the row-level security context inside the current transaction, for the few operations that do not run as
 * the organization of the request.
 *
 * <ul>
 *   <li>{@link #lookup}: reads that must cross organizations because the organization is not known yet (login by
 *   e-mail, the unique e-mail check, the API key lookup by hash). The {@code auth_lookup_policy} policies allow them,
 *   read-only, while {@code app.auth_lookup} is on.</li>
 *   <li>{@link #callAs}: work for another organization than the request's (the administrator created at sign-up,
 *   the last login and last use timestamps).</li>
 * </ul>
 *
 * <p>Why this matters: in development the database user is a superuser, which ignores row-level security. In
 * production (RDS) it is not, so every table with {@code FORCE ROW LEVEL SECURITY} only returns or accepts rows of
 * {@code app.current_tenant}. Both methods need an active transaction, because {@code set_config(..., true)} lasts
 * until the transaction ends and outside one it would be gone before the next statement.
 */
@Component
public class TenantSession {

    static final String TENANT_SETTING = "app.current_tenant";
    static final String AUTH_LOOKUP_SETTING = "app.auth_lookup";

    private final EntityManager entityManager;

    public TenantSession(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Runs a read that may see rows of any organization (authentication only). */
    public <T> T lookup(Supplier<T> query) {
        requireTransaction();
        set(AUTH_LOOKUP_SETTING, "on");
        T result;
        try {
            result = query.get();
        } catch (RuntimeException failure) {
            resetQuietly(AUTH_LOOKUP_SETTING, "off", failure);
            throw failure;
        }
        set(AUTH_LOOKUP_SETTING, "off");
        return result;
    }

    /**
     * Runs the action as the given organization and flushes its writes before going back to the previous one.
     * The setting changes before the action runs, so entities it modifies are written under the right organization.
     */
    public <T> T callAs(UUID organizationId, Supplier<T> action) {
        requireTransaction();
        String previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(organizationId.toString());
        try {
            set(TENANT_SETTING, organizationId.toString());
            T result = action.get();
            entityManager.flush();
            set(TENANT_SETTING, previous == null ? "" : previous);
            return result;
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentTenant(previous);
            }
        }
    }

    private void set(String name, String value) {
        entityManager.createNativeQuery("SELECT set_config(:name, :value, true)")
                .setParameter("name", name)
                .setParameter("value", value)
                .getSingleResult();
    }

    /** Best effort after a failure: the transaction may already be aborted, and the original error matters more. */
    private void resetQuietly(String name, String value, RuntimeException failure) {
        try {
            set(name, value);
        } catch (RuntimeException resetFailure) {
            failure.addSuppressed(resetFailure);
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TenantSession precisa de uma transação ativa (@Transactional).");
        }
    }
}
