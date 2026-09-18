package com.aegis.core.scoring;

import java.time.Duration;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static com.aegis.core.matching.MatchingDtos.MatchConfidence.POSSIBLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the {@code aegis.scoring.*} contract itself: that the YAML keys in application.yml actually
 * reach the record. A typo here would silently leave the shipped defaults in place, which is the
 * failure mode a configurable model must not have — the operator would believe they had retuned it.
 */
class ScoringPropertiesBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindingConfig.class);

    @Test
    void bindsEveryKnobFromKebabCasePropertyKeys() {
        runner.withPropertyValues(
                        "aegis.scoring.criticality.critical=0.9",
                        "aegis.scoring.criticality.low=0.05",
                        "aegis.scoring.exposure.internet-facing=0.8",
                        "aegis.scoring.exposure.isolated=0.1",
                        "aegis.scoring.confidence.possible=0.5",
                        "aegis.scoring.unknown-severity-factor=0.3",
                        "aegis.scoring.materiality-threshold=25",
                        "aegis.scoring.tiers.critical=90",
                        "aegis.scoring.tiers.high=70",
                        "aegis.scoring.rollup.max-signals=42",
                        "aegis.scoring.rollup.default-window=P7D")
                .run(context -> {
                    ScoringProperties properties = context.getBean(ScoringProperties.class);

                    assertThat(properties.criticalityFactor(BusinessCriticality.CRITICAL)).isEqualTo(0.9);
                    assertThat(properties.criticalityFactor(BusinessCriticality.LOW)).isEqualTo(0.05);
                    // Relaxed binding: the enum constant INTERNET_FACING is written internet-facing.
                    assertThat(properties.exposureFactor(ServiceExposure.INTERNET_FACING)).isEqualTo(0.8);
                    assertThat(properties.exposureFactor(ServiceExposure.ISOLATED)).isEqualTo(0.1);
                    assertThat(properties.confidenceFactor(POSSIBLE)).isEqualTo(0.5);
                    assertThat(properties.unknownSeverityFactor()).isEqualTo(0.3);
                    assertThat(properties.materialityThreshold()).isEqualTo(25.0);
                    assertThat(properties.tiers().critical()).isEqualTo(90.0);
                    assertThat(properties.tiers().high()).isEqualTo(70.0);
                    // Not named, so still the default rather than zero.
                    assertThat(properties.tiers().medium()).isEqualTo(25.0);
                    assertThat(properties.rollup().maxSignals()).isEqualTo(42);
                    assertThat(properties.rollup().defaultWindow()).isEqualTo(Duration.ofDays(7));
                });
    }

    @Test
    void bindsToTheShippedModelWhenNothingIsConfigured() {
        runner.run(context -> {
            ScoringProperties properties = context.getBean(ScoringProperties.class);

            assertThat(properties.criticalityFactor(BusinessCriticality.CRITICAL)).isEqualTo(1.0);
            assertThat(properties.exposureFactor(ServiceExposure.INTERNET_FACING)).isEqualTo(1.0);
            assertThat(properties.materialityThreshold()).isEqualTo(10.0);
            assertThat(properties.rollup().maxSignals()).isEqualTo(200);
        });
    }

    @Configuration
    @EnableConfigurationProperties(ScoringProperties.class)
    static class BindingConfig {
    }
}
