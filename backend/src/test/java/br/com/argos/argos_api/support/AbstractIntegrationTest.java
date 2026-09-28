package br.com.argos.argos_api.support;

import br.com.argos.argos_api.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

/**
 * Base for integration tests: real PostgreSQL (Testcontainers) with the Flyway migrations,
 * organization A with one user per role and organization B with an admin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    protected UUID orgA;
    protected UUID orgB;
    protected UUID adminA;
    protected UUID editorA;
    protected UUID viewerA;
    protected UUID executiveA;
    protected UUID adminB;

    @BeforeEach
    void setUpOrganizations() {
        TestFixtures.cleanDatabase(jdbc);
        orgA = TestFixtures.createOrganization(jdbc, "Loja A");
        orgB = TestFixtures.createOrganization(jdbc, "Loja B");
        adminA = TestFixtures.createUser(jdbc, orgA, "ADMIN");
        editorA = TestFixtures.createUser(jdbc, orgA, "EDITOR");
        viewerA = TestFixtures.createUser(jdbc, orgA, "VIEWER");
        executiveA = TestFixtures.createUser(jdbc, orgA, "EXECUTIVE");
        adminB = TestFixtures.createUser(jdbc, orgB, "ADMIN");
    }

    protected RequestPostProcessor asAdminA() {
        return TestFixtures.token(adminA, orgA, "ADMIN");
    }

    protected RequestPostProcessor asEditorA() {
        return TestFixtures.token(editorA, orgA, "EDITOR");
    }

    protected RequestPostProcessor asViewerA() {
        return TestFixtures.token(viewerA, orgA, "VIEWER");
    }

    protected RequestPostProcessor asExecutiveA() {
        return TestFixtures.token(executiveA, orgA, "EXECUTIVE");
    }

    protected RequestPostProcessor asAdminB() {
        return TestFixtures.token(adminB, orgB, "ADMIN");
    }
}
