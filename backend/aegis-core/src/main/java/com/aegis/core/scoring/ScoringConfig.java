package com.aegis.core.scoring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the model's weighting. Story CS203-66 replaces this with configuration binding; until then
 * the shipped defaults are the single source of the numbers, and {@link ImpactScorer} takes them as a
 * dependency rather than hard-coding them so that swap changes nothing else.
 */
@Configuration
class ScoringConfig {

    @Bean
    ScoringWeights scoringWeights() {
        return ScoringWeights.defaults();
    }
}
