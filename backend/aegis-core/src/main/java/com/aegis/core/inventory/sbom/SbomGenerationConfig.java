package com.aegis.core.inventory.sbom;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link SbomGenerationProperties} so {@code aegis.inventory.sbom-generation.*} binds. */
@Configuration
@EnableConfigurationProperties(SbomGenerationProperties.class)
class SbomGenerationConfig {
}
