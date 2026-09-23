package com.aegis.core.ingestion.connectors.nvd;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Registers {@link NvdProperties} and the connector's own HTTP client. */
@Configuration
@EnableConfigurationProperties(NvdProperties.class)
class NvdConfig {

    /**
     * Built here rather than reusing the application's other {@code RestClient} bean, which targets
     * the internal ai-service and carries its shared service token as a default header.
     */
    @Bean
    @ConditionalOnProperty(name = "aegis.ingestion.nvd.enabled", havingValue = "true")
    NvdApiClient nvdApiClient(
            NvdProperties properties, ObjectMapper objectMapper, ObservationRegistry observationRegistry) {
        return new NvdApiClient(properties, objectMapper, observationRegistry);
    }
}
