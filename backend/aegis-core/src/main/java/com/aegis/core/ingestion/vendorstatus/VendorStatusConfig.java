package com.aegis.core.ingestion.vendorstatus;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link VendorStatusProperties} and the connector's own HTTP client. */
@Configuration
@EnableConfigurationProperties(VendorStatusProperties.class)
class VendorStatusConfig {

    /**
     * Built here rather than reusing the application's other {@code RestClient} bean, which targets
     * the internal ai-service and carries its shared service token as a default header.
     */
    @Bean
    @ConditionalOnProperty(name = "aegis.ingestion.vendor-status.enabled", havingValue = "true")
    VendorStatusClient vendorStatusClient(
            VendorStatusProperties properties, ObservationRegistry observationRegistry) {
        return new VendorStatusClient(properties, observationRegistry);
    }
}
