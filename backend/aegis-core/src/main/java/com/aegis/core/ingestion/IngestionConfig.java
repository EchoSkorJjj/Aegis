package com.aegis.core.ingestion;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link IngestionProperties} so the framework beans can bind {@code aegis.ingestion.*}. */
@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
class IngestionConfig {}
