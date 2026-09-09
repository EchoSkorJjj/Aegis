package com.aegis.core.ai;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} used to reach the internal ai-service: base URL plus the shared
 * service token (not a user JWT — the ai-service is a compute dependency, ignorant of users).
 *
 * <p>Uses the static {@link RestClient#builder()} rather than injecting an auto-configured
 * {@code RestClient.Builder} bean — Spring Boot 4 does not expose one here. The {@link
 * ObservationRegistry} (always present with Micrometer) is passed in so calls are still traced
 * through to the ai-service.
 */
@Configuration
public class AiClientConfig {

    @Bean
    public RestClient aiRestClient(
            ObservationRegistry observationRegistry,
            @Value("${aegis.ai.base-url:http://localhost:8083}") String baseUrl,
            @Value("${aegis.ai.service-token:dev-internal-token-change-me}") String serviceToken) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Service-Token", serviceToken)
                .observationRegistry(observationRegistry)
                .build();
    }
}
