package com.aegis.core.scoring;

import java.math.BigDecimal;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
import com.aegis.core.scoring.ScoringDtos.SeveritySource;
import org.junit.jupiter.api.Test;

import static com.aegis.core.matching.MatchingDtos.MatchConfidence.CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ImpactScorerTest {

    private final ImpactScorer scorer = new ImpactScorer(ScoringProperties.defaults());

    @Test
    void multipliesSeverityExposureAndCriticalityOntoAHundredPointScale() {
        ScoreBreakdown breakdown = scorer.breakdown(
                1.0, ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL, MatchConfidence.CONFIRMED);

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
                scorer.breakdown(severity, ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL, CONFIRMED));
        double internalCritical = scorer.score(
                scorer.breakdown(severity, ServiceExposure.INTERNAL, BusinessCriticality.CRITICAL, CONFIRMED));
        double isolatedLow = scorer.score(
                scorer.breakdown(severity, ServiceExposure.ISOLATED, BusinessCriticality.LOW, CONFIRMED));

        assertThat(publicCritical).isGreaterThan(internalCritical).isGreaterThan(isolatedLow);
        // Internal costs CVSS's own Adjacent-network discount (0.9), not a band: still CRITICAL.
        assertThat(internalCritical).isEqualTo(90.0);
        // 0.5 (ISOLATED) x 0.25 (LOW) = 12.5 — a 10.0 stays material even on the least exposed, least
        // important kind of service.
        assertThat(isolatedLow).isEqualTo(12.5);
        assertThat(scorer.tier(internalCritical)).isEqualTo(ImpactTier.CRITICAL);
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
        // A no-impact notice sits under the materiality threshold even on a worst-case service.
        assertThat(scorer.severityFromLabel("none").factor()).isEqualTo(0.05);
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
        ScoreBreakdown declared = scorer.breakdown(1.0, ServiceExposure.INTERNAL, BusinessCriticality.HIGH, CONFIRMED);
        ScoreBreakdown missing = scorer.breakdown(1.0, null, BusinessCriticality.HIGH, CONFIRMED);

        assertThat(scorer.score(missing)).isEqualTo(scorer.score(declared));
    }

    @Test
    void bandsScoresIntoTiersAtTheConfiguredBoundaries() {
        // The CVSS v3.1 bands scaled to 0-100: 9.0 / 7.0 / 4.0.
        assertThat(scorer.tier(90.0)).isEqualTo(ImpactTier.CRITICAL);
        assertThat(scorer.tier(89.9)).isEqualTo(ImpactTier.HIGH);
        assertThat(scorer.tier(70.0)).isEqualTo(ImpactTier.HIGH);
        assertThat(scorer.tier(69.9)).isEqualTo(ImpactTier.MEDIUM);
        assertThat(scorer.tier(40.0)).isEqualTo(ImpactTier.MEDIUM);
        assertThat(scorer.tier(39.9)).isEqualTo(ImpactTier.LOW);
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
                scorer.breakdown(0.25, ServiceExposure.ISOLATED, BusinessCriticality.LOW, CONFIRMED));
        assertThat(noise).isEqualTo(3.1);
        assertThat(scorer.material(noise)).isFalse();
    }

    @Test
    void roundsToOneDecimalAndStaysWithinTheScale() {
        // 0.3 x 0.9 x 0.25 = 0.0675 -> 6.75 -> 6.8 (half up)
        assertThat(scorer.score(scorer.breakdown(0.3, ServiceExposure.INTERNAL, BusinessCriticality.LOW, CONFIRMED)))
                .isEqualTo(6.8);
        // Out-of-range severity is clamped, never allowed to push a score past 100 or below 0.
        assertThat(scorer.score(
                        scorer.breakdown(4.0, ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL, CONFIRMED)))
                .isEqualTo(100.0);
        assertThat(scorer.score(
                        scorer.breakdown(-1.0, ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL, CONFIRMED)))
                .isEqualTo(0.0);
    }

    @Test
    void honoursSuppliedWeightsRatherThanTheDefaults() {
        ScoringProperties strict = new ScoringProperties(
                null, null, null, null, 0.5, new ScoringProperties.Tiers(95.0, 80.0, 50.0, 20.0), 20.0, null);
        ImpactScorer strictScorer = new ImpactScorer(strict);

        assertThat(scorer.tier(90.0)).isEqualTo(ImpactTier.CRITICAL);
        assertThat(strictScorer.tier(90.0)).isEqualTo(ImpactTier.HIGH);
        assertThat(strictScorer.tier(95.0)).isEqualTo(ImpactTier.CRITICAL);
        assertThat(strictScorer.material(15.0)).isFalse();
        assertThat(scorer.material(15.0)).isTrue();
    }
}
