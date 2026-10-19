package com.aegis.core.scoring;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link ScoringProperties} so the model binds {@code aegis.scoring.*}. */
@Configuration
@EnableConfigurationProperties(ScoringProperties.class)
class ScoringConfig {}
