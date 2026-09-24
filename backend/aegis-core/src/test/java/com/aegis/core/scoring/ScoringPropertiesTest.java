package com.aegis.core.scoring;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import org.junit.jupiter.api.Test;

import static com.aegis.core.matching.MatchingDtos.MatchConfidence.CONFIRMED;
import static com.aegis.core.matching.MatchingDtos.MatchConfidence.POSSIBLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoringPropertiesTest {

    @Test
    void anEmptyConfigurationBlockBindsToTheShippedModel() {
        ScoringProperties defaults = ScoringProperties.defaults();

        assertThat(defaults.criticalityFactor(BusinessCriticality.CRITICAL)).isEqualTo(1.0);
        assertThat(defaults.criticalityFactor(BusinessCriticality.LOW)).isEqualTo(0.25);
        assertThat(defaults.exposureFactor(ServiceExposure.INTERNET_FACING)).isEqualTo(1.0);
        assertThat(defaults.exposureFactor(ServiceExposure.ISOLATED)).isEqualTo(0.5);
        assertThat(defaults.unknownSeverityFactor()).isEqualTo(0.5);
        assertThat(defaults.materialityThreshold()).isEqualTo(10.0);
        assertThat(defaults.tiers().critical()).isEqualTo(90.0);
        assertThat(defaults.rollup().maxSignals()).isEqualTo(200);
        assertThat(defaults.rollup().defaultWindow()).isEqualTo(Duration.ofDays(30));
        // Confidence is reported, not discounted, unless a deployment says otherwise.
        assertThat(defaults.confidenceFactor(CONFIRMED)).isEqualTo(1.0);
        assertThat(defaults.confidenceFactor(POSSIBLE)).isEqualTo(1.0);
        // The severity-label vocabulary ships too, so a no-CVSS signal still scores.
        assertThat(defaults.severityLabelFactor("critical")).isEqualTo(0.95);
        assertThat(defaults.severityLabelFactor("major")).isEqualTo(0.75);
    }

    @Test
    void aPartialOverrideChangesOnlyWhatItNames() {
        Map<BusinessCriticality, Double> override = new EnumMap<>(BusinessCriticality.class);
        override.put(BusinessCriticality.LOW, 0.05);

        ScoringProperties tuned = new ScoringProperties(override, null, null, null, null, null, null, null);

        assertThat(tuned.criticalityFactor(BusinessCriticality.LOW)).isEqualTo(0.05);
        // The entries it did not mention keep their defaults rather than vanishing.
        assertThat(tuned.criticalityFactor(BusinessCriticality.CRITICAL)).isEqualTo(1.0);
        assertThat(tuned.criticalityFactor(BusinessCriticality.HIGH)).isEqualTo(0.8);
        assertThat(tuned.criticalityFactor(BusinessCriticality.MEDIUM)).isEqualTo(0.5);
        assertThat(tuned.exposureFactor(ServiceExposure.INTERNAL)).isEqualTo(0.9);
    }

    @Test
    void appliesADeploymentsConfidenceDiscountToTheScore() {
        Map<MatchConfidence, Double> halved = new EnumMap<>(MatchConfidence.class);
        halved.put(POSSIBLE, 0.5);
        ImpactScorer strict = new ImpactScorer(
                new ScoringProperties(null, null, halved, null, null, null, null, null));
        ImpactScorer shipped = new ImpactScorer(ScoringProperties.defaults());

        double confirmed = strict.score(strict.breakdown(
                1.0, ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL, CONFIRMED));
        double possible = strict.score(strict.breakdown(
                1.0, ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL, POSSIBLE));

        assertThat(confirmed).isEqualTo(100.0);
        assertThat(possible).isEqualTo(50.0);
        // The shipped model leaves both alone, so enabling the discount is a deliberate act.
        assertThat(shipped.score(shipped.breakdown(
                        1.0, ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL, POSSIBLE)))
                .isEqualTo(100.0);
    }

    @Test
    void fallsBackToTheDefaultForAnAbsentOrNegativeScalar() {
        ScoringProperties broken = new ScoringProperties(
                Map.of(), Map.of(), Map.of(), null, -4.0,
                new ScoringProperties.Tiers(null, -1.0, null, null), -2.0,
                new ScoringProperties.Rollup(-5, null));

        assertThat(broken.unknownSeverityFactor()).isEqualTo(0.5);
        assertThat(broken.materialityThreshold()).isEqualTo(10.0);
        assertThat(broken.tiers().high()).isEqualTo(70.0);
        assertThat(broken.rollup().maxSignals()).isEqualTo(200);
        assertThat(broken.criticalityFactor(BusinessCriticality.HIGH)).isEqualTo(0.8);
    }

    @Test
    void clampsAnOutOfRangeWeightIntoTheValidInterval() {
        // A present-but-out-of-range weight has a safe reading — cap it — so the app still starts.
        Map<BusinessCriticality, Double> tooHigh = new EnumMap<>(BusinessCriticality.class);
        tooHigh.put(BusinessCriticality.HIGH, 5.0); // a multiplier, not a rocket booster
        Map<MatchConfidence, Double> negative = new EnumMap<>(MatchConfidence.class);
        negative.put(POSSIBLE, -1.0);

        ScoringProperties clamped = new ScoringProperties(
                tooHigh, null, negative, null, 4.0, null, 250.0, null);

        assertThat(clamped.criticalityFactor(BusinessCriticality.HIGH)).isEqualTo(1.0); // 5.0 -> 1.0
        assertThat(clamped.confidenceFactor(POSSIBLE)).isEqualTo(0.0);                  // -1.0 -> 0.0
        assertThat(clamped.unknownSeverityFactor()).isEqualTo(1.0);                     // 4.0 -> 1.0
        assertThat(clamped.materialityThreshold()).isEqualTo(100.0);                    // 250 -> 100
    }

    @Test
    void failsFastOnABrokenTierLadderRatherThanMisTieringInSilence() {
        // A non-descending ladder has no safe reading: every score in the inversion would mis-tier.
        assertThatThrownBy(() -> new ScoringProperties.Tiers(75.0, 80.0, 25.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("descending");
        // Nor a bound above the top of the 0-100 scale, which no score can reach.
        assertThatThrownBy(() -> new ScoringProperties.Tiers(150.0, 50.0, 25.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class);
        // A descending ladder within range is accepted.
        assertThatCode(() -> new ScoringProperties.Tiers(90.0, 60.0, 30.0, 10.0)).doesNotThrowAnyException();
    }

    @Test
    void extendsTheSeverityLabelVocabularyFromConfiguration() {
        Map<String, Double> vocab = Map.of("sev1", 0.9, "critical", 0.99);
        ScoringProperties tuned = new ScoringProperties(null, null, null, vocab, null, null, null, null);

        assertThat(tuned.severityLabelFactor("sev1")).isEqualTo(0.9);      // a feed's own word
        assertThat(tuned.severityLabelFactor("critical")).isEqualTo(0.99); // overriding a shipped word
        assertThat(tuned.severityLabelFactor("high")).isEqualTo(0.75);     // an unmentioned word keeps its default
        assertThat(tuned.severityLabelFactor("nonsense")).isNull();        // unknown -> caller falls back
    }

    @Test
    void reweightingTheTiersRebandsTheSameScore() {
        ImpactScorer lenient = new ImpactScorer(new ScoringProperties(
                null, null, null, null, null, new ScoringProperties.Tiers(95.0, 85.0, 70.0, 50.0), null, null));

        assertThat(lenient.tier(80.0)).isEqualTo(ImpactTier.MEDIUM);
        assertThat(new ImpactScorer(ScoringProperties.defaults()).tier(80.0)).isEqualTo(ImpactTier.HIGH);
    }
}
