package com.aegis.core.ai;

import java.time.Duration;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} used to reach the internal ai-service: base URL plus the shared
 * service token (not a user JWT — the ai-service is a compute dependency, ignorant of users).
 *
 * <p>Uses the static {@link RestClient#builder()} rather than injecting an auto-configured
 * {@code RestClient.Builder} bean — Spring Boot 4 does not expose one here. The {@link
 * ObservationRegistry} (always present with Micrometer) is passed in so calls are still traced
 * through to the ai-service.
 *
 * <p>Connect and read timeouts are set explicitly so a slow or hung ai-service can never block a
 * request thread indefinitely. The read timeout is deliberately generous because the assess/draft
 * calls are LLM-backed; when those land, raise it (or set a per-call timeout) for those endpoints.
 */
@Configuration
public class AiClientConfig {

    @Bean
    public RestClient aiRestClient(
            ObservationRegistry observationRegistry,
            @Value("${aegis.ai.base-url:http://localhost:8083}") String baseUrl,
            @Value("${aegis.ai.service-token:dev-internal-token-change-me}") String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Service-Token", serviceToken)
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
    }
}
