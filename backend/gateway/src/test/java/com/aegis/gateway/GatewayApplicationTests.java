package com.aegis.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the whole gateway context boots: the WebMVC gateway route beans, the servlet Spring
 * Security resource-server chain (lazy JWT decoder — no identity-service needed at startup), and the
 * actuator/observability wiring. No database or downstream service is required.
 */
@SpringBootTest
class GatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}
