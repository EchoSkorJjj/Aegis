package com.aegis.core.support;

import javax.sql.DataSource;

import com.aegis.core.inventory.sbom.SbomTestConfig;
import com.aegis.core.matching.MatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Base for integration tests: boots the full application context against a real PostgreSQL
 * (Testcontainers), so Flyway migrations, Hibernate schema validation and the Spring Security
 * filter chain are all genuinely exercised. Requires a running Docker daemon.
 *
 * <p>Plain {@code postgres:16} — the core has no pgvector dependency (RAG lives in the ai-service).
 *
 * <p>The application connects as a plain role ({@code testcontainers/app-role.sql}), as it does when deployed:
 * the container's own user is a superuser, and a superuser skips every row-level security policy, so the org
 * isolation of V9 onwards would go untested. A test that checks the schema itself, past those policies, uses
 * {@link #superuserDataSource()}.
 */
@SpringBootTest
@Import({IdentityTestConfig.class, ActionsTestConfig.class, SbomTestConfig.class})
// What reaches outside (GitHub, Jira, the feeds) and the demo seed stay off, whatever the shell running the tests
// exported: application.yml reads AEGIS_GITHUB_ENABLED and the like from the environment, and these outrank it. A
// test that needs one on sets it in its own @TestPropertySource, which outranks these.
@TestPropertySource(properties = {
        "aegis.actions.github.enabled=false",
        "aegis.actions.jira.enabled=false",
        "aegis.ingestion.nvd.enabled=false",
        "aegis.ingestion.osv.enabled=false",
        "aegis.ingestion.vendor-status.enabled=false",
        "aegis.ingestion.news.enabled=false",
        "aegis.demo.seed-enabled=false",
        "aegis.notifications.enabled=false",
        "aegis.assess.fix-commits.enabled=false"})
public abstract class AbstractIntegrationTest {

    private static final String APP_ROLE = "aegis";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16")
            .withInitScript("testcontainers/app-role.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE);
    }

    /**
     * The database as its superuser sees it: past every row-level security policy. Only for a test about the
     * schema itself — a constraint, a migration's backfill — never for what the application may see.
     */
    protected static DataSource superuserDataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    /**
     * A pass-through spy on matching, for a test that must make a match fail inside matching's own
     * transaction (the roll-up resilience test). Declared here rather than in that test because a bean
     * override is part of the context cache key: in one class it would boot a second application
     * context just for that class. It behaves as the real bean unless stubbed; stubbing resets after
     * each test.
     */
    @MockitoSpyBean
    protected MatchingService matchingSpy;

    protected MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }
}
