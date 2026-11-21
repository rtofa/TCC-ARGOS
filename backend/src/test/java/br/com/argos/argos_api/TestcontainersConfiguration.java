package br.com.argos.argos_api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection(name = "postgres") // custom image: say which service it is
    PostgreSQLContainer postgresContainer() {
        // Same image as docker-compose: PostgreSQL 15 with pgvector (hybrid search of the support agent).
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg15-trixie").asCompatibleSubstituteFor("postgres"))
                .withSharedMemorySize(512L * 1024L * 1024L);
    }
}
