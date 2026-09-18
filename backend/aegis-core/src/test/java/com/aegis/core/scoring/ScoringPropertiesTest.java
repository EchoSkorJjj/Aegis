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

class ScoringPropertiesTest {

    @Test
    void anEmptyConfigurationBlockBindsToTheShippedModel() {
        ScoringProperties defaults = ScoringProperties.defaults();

        assertThat(defaults.criticalityFactor(BusinessCriticality.CRITICAL)).isEqualTo(1.0);
        assertThat(defaults.criticalityFactor(BusinessCriticality.LOW)).isEqualTo(0.25);
        assertThat(defaults.exposureFactor(ServiceExposure.INTERNET_FACING)).isEqualTo(1.0);
        assertThat(defaults.exposureFactor(ServiceExposure.ISOLATED)).isEqualTo(0.4);
        assertThat(defaults.unknownSeverityFactor()).isEqualTo(0.5);
        assertThat(defaults.materialityThreshold()).isEqualTo(10.0);
        assertThat(defaults.tiers().critical()).isEqualTo(75.0);
        assertThat(defaults.rollup().maxSignals()).isEqualTo(200);
        assertThat(defaults.rollup().defaultWindow()).isEqualTo(Duration.ofDays(30));
        // Confidence is reported, not discounted, unless a deployment says otherwise.
        assertThat(defaults.confidenceFactor(CONFIRMED)).isEqualTo(1.0);
        assertThat(defaults.confidenceFactor(POSSIBLE)).isEqualTo(1.0);
    }

    @Test
    void aPartialOverrideChangesOnlyWhatItNames() {
        Map<BusinessCriticality, Double> override = new EnumMap<>(BusinessCriticality.class);
        override.put(BusinessCriticality.LOW, 0.05);

        ScoringProperties tuned = new ScoringProperties(override, null, null, null, null, null, null);

        assertThat(tuned.criticalityFactor(BusinessCriticality.LOW)).isEqualTo(0.05);
        // The entries it did not mention keep their defaults rather than vanishing.
        assertThat(tuned.criticalityFactor(BusinessCriticality.CRITICAL)).isEqualTo(1.0);
        assertThat(tuned.criticalityFactor(BusinessCriticality.HIGH)).isEqualTo(0.75);
        assertThat(tuned.criticalityFactor(BusinessCriticality.MEDIUM)).isEqualTo(0.5);
        assertThat(tuned.exposureFactor(ServiceExposure.INTERNAL)).isEqualTo(0.6);
    }

    @Test
    void appliesADeploymentsConfidenceDiscountToTheScore() {
        Map<MatchConfidence, Double> halved = new EnumMap<>(MatchConfidence.class);
        halved.put(POSSIBLE, 0.5);
        ImpactScorer strict = new ImpactScorer(
                new ScoringProperties(null, null, halved, null, null, null, null));
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
    void rejectsNonsensicalValuesByFallingBackToTheDefault() {
        ScoringProperties broken = new ScoringProperties(
                Map.of(), Map.of(), Map.of(), -4.0, new ScoringProperties.Tiers(null, -1.0, null, null), -2.0,
                new ScoringProperties.Rollup(-5, null));

        assertThat(broken.unknownSeverityFactor()).isEqualTo(0.5);
        assertThat(broken.materialityThreshold()).isEqualTo(10.0);
        assertThat(broken.tiers().high()).isEqualTo(50.0);
        assertThat(broken.rollup().maxSignals()).isEqualTo(200);
        assertThat(broken.criticalityFactor(BusinessCriticality.HIGH)).isEqualTo(0.75);
    }

    @Test
    void reweightingTheTiersRebandsTheSameScore() {
        ImpactScorer lenient = new ImpactScorer(new ScoringProperties(
                null, null, null, null, new ScoringProperties.Tiers(95.0, 85.0, 70.0, 50.0), null, null));

        assertThat(lenient.tier(80.0)).isEqualTo(ImpactTier.MEDIUM);
        assertThat(new ImpactScorer(ScoringProperties.defaults()).tier(80.0)).isEqualTo(ImpactTier.CRITICAL);
    }
}
