package com.aegis.core;

import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the scaffold: the context boots against a real PostgreSQL with Flyway and Hibernate
 * validation, the health endpoint is public, and an anonymous request to a protected path is rejected
 * with 401 — proving the resource-server security wiring (and that the AI port bean wires cleanly).
 * Requires a running Docker daemon.
 */
class AegisCoreApplicationTests extends AbstractIntegrationTest {

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void protectedRouteRequiresAuthentication() throws Exception {
        // The test endpoint under /api is JWT-protected; an anonymous request is rejected with 401.
        mvc.perform(get("/api/ping")).andExpect(status().isUnauthorized());
    }
}
