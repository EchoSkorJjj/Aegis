package com.aegis.core.scoring;

import java.math.BigDecimal;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
import com.aegis.core.scoring.ScoringDtos.SeveritySource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ImpactScorerTest {

    private final ImpactScorer scorer = new ImpactScorer(ScoringWeights.defaults());

    @Test
    void multipliesSeverityExposureAndCriticalityOntoAHundredPointScale() {
        ScoreBreakdown breakdown = scorer.breakdown(
                1.0, ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL);

        assertThat(breakdown.severityFactor()).isEqualTo(1.0);
        assertThat(breakdown.exposureFactor()).isEqualTo(1.0);
        assertThat(breakdown.criticalityFactor()).isEqualTo(1.0);
        // The worst case is exactly 100, so every other score reads as a percentage of it.
        assertThat(scorer.score(breakdown)).isEqualTo(100.0);
        assertThat(scorer.tier(100.0)).isEqualTo(ImpactTier.CRITICAL);
    }

    @Test
    void ranksTheSameVulnerabilityLowerOnALessExposedOrLessCriticalService() {
        // One CVSS 10.0 vulnerability, three services: only the estate attributes differ.
        double severity = 1.0;
        double publicCritical = scorer.score(
                scorer.breakdown(severity, ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL));
        double internalCritical = scorer.score(
                scorer.breakdown(severity, ServiceExposure.INTERNAL, BusinessCriticality.CRITICAL));
        double isolatedLow = scorer.score(
                scorer.breakdown(severity, ServiceExposure.ISOLATED, BusinessCriticality.LOW));

        assertThat(publicCritical).isGreaterThan(internalCritical).isGreaterThan(isolatedLow);
        assertThat(internalCritical).isEqualTo(60.0);
        assertThat(isolatedLow).isEqualTo(10.0);
        assertThat(scorer.tier(internalCritical)).isEqualTo(ImpactTier.HIGH);
        assertThat(scorer.tier(isolatedLow)).isEqualTo(ImpactTier.LOW);
    }

    @Test
    void rescalesACvssBaseScoreWithoutReweightingIt() {
        SeverityInput severity = scorer.severityFromCvss("3.1", new BigDecimal("7.5"));

        assertThat(severity.source()).isEqualTo(SeveritySource.CVSS);
        assertThat(severity.cvssVersion()).isEqualTo("3.1");
        assertThat(severity.cvssBaseScore()).isEqualByComparingTo("7.5");
        assertThat(severity.factor()).isEqualTo(0.75, within(1e-9));
    }

    @Test
    void readsBothNvdAndStatuspageSeverityVocabulariesWhenThereIsNoCvss() {
        assertThat(scorer.severityFromLabel("CRITICAL").factor()).isEqualTo(0.95);
        assertThat(scorer.severityFromLabel("major").factor()).isEqualTo(0.75);
        assertThat(scorer.severityFromLabel("  Minor  ").factor()).isEqualTo(0.5);
        assertThat(scorer.severityFromLabel("none").factor()).isEqualTo(0.1);
        assertThat(scorer.severityFromLabel("HIGH").source()).isEqualTo(SeveritySource.SEVERITY_LABEL);
    }

    @Test
    void treatsAnAbsentOrUnrecognisedSeverityAsNeutralRatherThanHarmless() {
        SeverityInput absent = scorer.severityFromLabel(null);
        SeverityInput unrecognised = scorer.severityFromLabel("catastrophic-ish");
        SeverityInput unscored = scorer.severityFromCvss("3.1", null);

        // Not knowing how bad something is must never rank it below something known to be mild.
        assertThat(absent.factor()).isEqualTo(0.5);
        assertThat(unrecognised.factor()).isEqualTo(0.5);
        assertThat(unscored.factor()).isEqualTo(0.5);
        assertThat(absent.source()).isEqualTo(SeveritySource.NONE);
        assertThat(unrecognised.source()).isEqualTo(SeveritySource.NONE);
        // The unusable label is still reported, so a reviewer can see what the feed actually said.
        assertThat(unrecognised.label()).isEqualTo("catastrophic-ish");
        assertThat(scorer.severityFromLabel("low").factor()).isLessThan(absent.factor());
    }

    @Test
    void appliesTheDefaultExposureWhenAServicePredatesTheAttribute() {
        ScoreBreakdown declared = scorer.breakdown(1.0, ServiceExposure.INTERNAL, BusinessCriticality.HIGH);
        ScoreBreakdown missing = scorer.breakdown(1.0, null, BusinessCriticality.HIGH);

        assertThat(scorer.score(missing)).isEqualTo(scorer.score(declared));
    }

    @Test
    void bandsScoresIntoTiersAtTheConfiguredBoundaries() {
        assertThat(scorer.tier(75.0)).isEqualTo(ImpactTier.CRITICAL);
        assertThat(scorer.tier(74.9)).isEqualTo(ImpactTier.HIGH);
        assertThat(scorer.tier(50.0)).isEqualTo(ImpactTier.HIGH);
        assertThat(scorer.tier(49.9)).isEqualTo(ImpactTier.MEDIUM);
        assertThat(scorer.tier(25.0)).isEqualTo(ImpactTier.MEDIUM);
        assertThat(scorer.tier(24.9)).isEqualTo(ImpactTier.LOW);
        assertThat(scorer.tier(10.0)).isEqualTo(ImpactTier.LOW);
        assertThat(scorer.tier(9.9)).isEqualTo(ImpactTier.NEGLIGIBLE);
        assertThat(scorer.tier(0.0)).isEqualTo(ImpactTier.NEGLIGIBLE);
    }

    @Test
    void marksScoresUnderTheMaterialityThresholdAsNoise() {
        assertThat(scorer.material(10.0)).isTrue();
        assertThat(scorer.material(9.9)).isFalse();

        // A trivial advisory on an isolated, low-tier service is exactly the noise this suppresses.
        double noise = scorer.score(
                scorer.breakdown(0.25, ServiceExposure.ISOLATED, BusinessCriticality.LOW));
        assertThat(noise).isEqualTo(2.5);
        assertThat(scorer.material(noise)).isFalse();
    }

    @Test
    void roundsToOneDecimalAndStaysWithinTheScale() {
        // 0.333 x 0.6 x 0.75 = 0.14985 -> 14.985 -> 15.0
        assertThat(scorer.score(scorer.breakdown(0.333, ServiceExposure.INTERNAL, BusinessCriticality.HIGH)))
                .isEqualTo(15.0);
        // Out-of-range severity is clamped, never allowed to push a score past 100 or below 0.
        assertThat(scorer.score(
                        scorer.breakdown(4.0, ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL)))
                .isEqualTo(100.0);
        assertThat(scorer.score(
                        scorer.breakdown(-1.0, ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL)))
                .isEqualTo(0.0);
    }

    @Test
    void honoursSuppliedWeightsRatherThanTheDefaults() {
        ScoringWeights strict = new ScoringWeights(
                null, null, 0.5, new ScoringWeights.Tiers(90, 70, 40, 20), 20.0);
        ImpactScorer strictScorer = new ImpactScorer(strict);

        assertThat(strictScorer.tier(75.0)).isEqualTo(ImpactTier.HIGH);
        assertThat(strictScorer.tier(90.0)).isEqualTo(ImpactTier.CRITICAL);
        assertThat(strictScorer.material(15.0)).isFalse();
        assertThat(scorer.material(15.0)).isTrue();
    }
}
