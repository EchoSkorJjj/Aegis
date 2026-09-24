package com.aegis.core.scoring;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.scoring.ScoringDtos.ScoreFactor;
import com.aegis.core.scoring.ScoringDtos.ScoreFactorKind;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
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
                        "aegis.scoring.severity-label.critical=0.99",
                        "aegis.scoring.severity-label.sev1=0.9",
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
                    // A shipped word can be re-weighted and a feed's own word added, both from config.
                    assertThat(properties.severityLabelFactor("critical")).isEqualTo(0.99);
                    assertThat(properties.severityLabelFactor("sev1")).isEqualTo(0.9);
                    assertThat(properties.severityLabelFactor("high")).isEqualTo(0.75); // unnamed keeps its default
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

    /**
     * A score's factors point at the setting that controls each weight (CS203-72). That pointer is only
     * useful if it is a real key: for every setting the explainer reports, binding a value to it must
     * move exactly that factor's weight.
     */
    @Test
    void everySettingAScoreFactorPointsAtIsARealKeyForThatFactor() {
        ImpactScorer shipped = new ImpactScorer(ScoringProperties.defaults());
        Map<String, Scenario> bySetting = new LinkedHashMap<>();
        for (String label : Arrays.asList("major", null)) {
            for (ServiceExposure exposure : ServiceExposure.values()) {
                for (BusinessCriticality criticality : BusinessCriticality.values()) {
                    for (MatchConfidence confidence : MatchConfidence.values()) {
                        Scenario scenario = new Scenario(label, exposure, criticality, confidence, null);
                        for (ScoreFactor factor : scenario.factors(shipped)) {
                            if (factor.setting() != null) {
                                bySetting.putIfAbsent(factor.setting(), scenario.blaming(factor.factor()));
                            }
                        }
                    }
                }
            }
        }
        // Severity word, the unknown fallback, 3 exposures, 4 criticalities, 2 confidences.
        assertThat(bySetting).hasSize(11);

        bySetting.forEach((setting, scenario) -> runner.withPropertyValues(setting + "=0.123").run(context -> {
            ImpactScorer retuned = new ImpactScorer(context.getBean(ScoringProperties.class));
            ScoreFactor factor = scenario.factors(retuned).stream()
                    .filter(candidate -> candidate.factor() == scenario.kind())
                    .findFirst()
                    .orElseThrow();
            assertThat(factor.weight()).as(setting).isEqualTo(0.123);
            assertThat(factor.setting()).isEqualTo(setting);
        }));
    }

    private record Scenario(
            String severityLabel,
            ServiceExposure exposure,
            BusinessCriticality criticality,
            MatchConfidence confidence,
            ScoreFactorKind kind) {

        Scenario blaming(ScoreFactorKind factor) {
            return new Scenario(severityLabel, exposure, criticality, confidence, factor);
        }

        List<ScoreFactor> factors(ImpactScorer scorer) {
            SeverityInput severity = scorer.severityFromLabel(severityLabel);
            ScoreBreakdown breakdown = scorer.breakdown(severity.factor(), exposure, criticality, confidence);
            return new ScoreExplainer(scorer).factors(severity, breakdown);
        }
    }

    @Configuration
    @EnableConfigurationProperties(ScoringProperties.class)
    static class BindingConfig {
    }
}
