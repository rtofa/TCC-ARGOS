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
        if (tenantId != null) {
            entityManager.createNativeQuery("SET LOCAL app.current_tenant = :tenantId")
                    .setParameter("tenantId", tenantId)
                    .executeUpdate();
        } else {
            entityManager.createNativeQuery("SET LOCAL app.current_tenant = ''")
                    .executeUpdate();
        }
    }
}
