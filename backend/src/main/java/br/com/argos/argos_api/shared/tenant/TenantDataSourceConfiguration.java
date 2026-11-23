package br.com.argos.argos_api.shared.tenant;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** Wraps the application's DataSource in {@link TenantAwareDataSource}. */
@Configuration(proxyBeanMethods = false)
class TenantDataSourceConfiguration {

    // Static: a BeanPostProcessor must exist before the DataSource is created, without pulling other beans early.
    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
