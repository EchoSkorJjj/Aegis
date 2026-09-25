package com.aegis.core.support;

import com.aegis.core.inventory.sbom.SbomTestConfig;
import com.aegis.core.matching.MatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 */
@SpringBootTest
@Import({IdentityTestConfig.class, ActionsTestConfig.class, SbomTestConfig.class})
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
