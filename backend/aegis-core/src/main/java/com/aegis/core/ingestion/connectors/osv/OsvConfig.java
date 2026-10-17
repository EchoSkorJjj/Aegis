package com.aegis.core.ingestion.connectors.osv;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link OsvProperties} and the connector's HTTP client. */
@Configuration
@EnableConfigurationProperties(OsvProperties.class)
class OsvConfig {

    @Bean
    @ConditionalOnProperty(name = "aegis.ingestion.osv.enabled", havingValue = "true")
    OsvClient osvClient(OsvProperties properties) {
        return new HttpOsvClient(properties);
    }
}
