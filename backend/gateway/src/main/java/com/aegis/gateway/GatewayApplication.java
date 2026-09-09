package com.aegis.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aegis API Gateway — the single entry point. Validates the JWT identity-service issues, applies
 * coarse route-level authorisation, and forwards to the right backend service. Fine-grained,
 * owner-scoped authorisation stays in each service (see {@code ARCHITECTURE.md}).
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
