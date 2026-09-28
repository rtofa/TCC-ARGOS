package br.com.argos.argos_api.shared.tenant;

import jakarta.persistence.EntityManager;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TenantAspect {

    private final EntityManager entityManager;

    public TenantAspect(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Before("execution(* org.springframework.data.repository.Repository+.*(..)) || " +
            "execution(* br.com.argos.argos_api..*Repository+.*(..))")
    public void setTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        // SET does not accept bind parameters in PostgreSQL; set_config does, and as a SELECT
        // it does not require an active transaction (is_local = true scopes it to the transaction).
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tenantId, true)")
                .setParameter("tenantId", tenantId != null ? tenantId : "")
                .getSingleResult();
    }
}
