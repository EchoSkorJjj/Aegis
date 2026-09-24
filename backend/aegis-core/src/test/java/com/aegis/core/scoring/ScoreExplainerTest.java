package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.scoring.ScoringDtos.ScoreFactor;
import com.aegis.core.scoring.ScoringDtos.ScoreFactorKind;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
import org.junit.jupiter.api.Test;

import static com.aegis.core.matching.MatchingDtos.MatchConfidence.CONFIRMED;
import static com.aegis.core.matching.MatchingDtos.MatchConfidence.POSSIBLE;
import static com.aegis.core.scoring.ScoringDtos.ScoreFactorKind.CONFIDENCE;
import static com.aegis.core.scoring.ScoringDtos.ScoreFactorKind.CRITICALITY;
import static com.aegis.core.scoring.ScoringDtos.ScoreFactorKind.EXPOSURE;
import static com.aegis.core.scoring.ScoringDtos.ScoreFactorKind.SEVERITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The "why this number" contract: every term surfaced with its input, weight and setting, headroom
 * that agrees with the scorer to the displayed decimal, and the factor holding the score down named.
 */
class ScoreExplainerTest {

    private final ImpactScorer scorer = new ImpactScorer(ScoringProperties.defaults());
    private final ScoreExplainer explainer = new ScoreExplainer(scorer);

    @Test
    void listsEveryTermInModelOrderWithItsInputWeightAndWhereTheWeightIsSet() {
        SeverityInput severity = scorer.severityFromCvss("3.1", new BigDecimal("8.0"));
        ScoreBreakdown breakdown = scorer.breakdown(
                severity.factor(), ServiceExposure.INTERNAL, BusinessCriticality.HIGH, CONFIRMED);

        List<ScoreFactor> factors = explainer.factors(severity, breakdown);

        assertThat(factors).extracting(ScoreFactor::factor)
                .containsExactly(SEVERITY, EXPOSURE, CRITICALITY, CONFIDENCE);
        assertThat(factors).extracting(ScoreFactor::input)
                .containsExactly("CVSS 3.1 base 8", "INTERNAL", "HIGH", "CONFIRMED");
        assertThat(factors).extracting(ScoreFactor::weight).containsExactly(0.8, 0.6, 0.75, 1.0);
        // A CVSS score is published by the feed, so nothing in configuration sets it; the rest are
        // the exact keys an operator would write in application.yml.
        assertThat(factors).extracting(ScoreFactor::setting).containsExactly(
                null,
                "aegis.scoring.exposure.internal",
                "aegis.scoring.criticality.high",
                "aegis.scoring.confidence.confirmed");
    }

    @Test
    void headroomIsWhatTheScoreWouldGainIfThatFactorAloneWereAtItsMaximum() {
        SeverityInput severity = scorer.severityFromCvss("3.1", new BigDecimal("8.0"));
        ScoreBreakdown breakdown = scorer.breakdown(
                severity.factor(), ServiceExposure.INTERNAL, BusinessCriticality.HIGH, CONFIRMED);

        // 0.8 x 0.6 x 0.75 = 36.0. Maxing each term alone: severity -> 45.0, exposure -> 60.0,
        // criticality -> 48.0, confidence is already 1.0.
        assertThat(scorer.score(breakdown)).isEqualTo(36.0);
        List<ScoreFactor> factors = explainer.factors(severity, breakdown);

        assertThat(factors).extracting(ScoreFactor::headroom).containsExactly(9.0, 24.0, 12.0, 0.0);
        // Exposure costs the most, so it is the one holding the score down.
        assertThat(factors).extracting(ScoreFactor::limiting).containsExactly(false, true, false, false);
        assertThat(explainer.explain("CVE-2099-1", "Orders", 36.0, ImpactTier.MEDIUM, breakdown, factors))
                .isEqualTo("CVE-2099-1 scores 36.0 (MEDIUM) for Orders: severity 0.80 (CVSS 3.1 base 8) "
                        + "x exposure 0.60 (INTERNAL) x criticality 0.75 (HIGH); "
                        + "exposure (INTERNAL) holds it down most, costing 24.0 points");
    }

    @Test
    void headroomAgreesWithTheScorerToTheDisplayedDecimalForEveryCombination() {
        // A discount on POSSIBLE so the confidence term is exercised too.
        ImpactScorer discounting = new ImpactScorer(new ScoringProperties(
                null, null, Map.of(POSSIBLE, 0.7), null, null, null, null, null));
        ScoreExplainer explaining = new ScoreExplainer(discounting);

        for (String cvss : List.of("0.0", "2.5", "5.5", "7.5", "9.8", "10.0")) {
            SeverityInput severity = discounting.severityFromCvss("3.1", new BigDecimal(cvss));
            for (ServiceExposure exposure : ServiceExposure.values()) {
                for (BusinessCriticality criticality : BusinessCriticality.values()) {
                    for (MatchConfidence confidence : MatchConfidence.values()) {
                        ScoreBreakdown breakdown = discounting.breakdown(
                                severity.factor(), exposure, criticality, confidence);
                        double score = discounting.score(breakdown);

                        for (ScoreFactor factor : explaining.factors(severity, breakdown)) {
                            // score + headroom is exactly what the reader would see after the change,
                            // rounding included — no 0.1 drift on a .x5 boundary.
                            assertThat(score + factor.headroom())
                                    .as("%s at CVSS %s / %s / %s / %s", factor.factor(), cvss, exposure,
                                            criticality, confidence)
                                    .isCloseTo(discounting.score(maximised(breakdown, factor.factor())),
                                            within(1e-9));
                            assertThat(factor.headroom()).isBetween(0.0, 100.0);
                        }
                    }
                }
            }
        }
    }

    @Test
    void headroomIsMeasuredAgainstTheRoundedScoreTheReaderSees() {
        // A "major" vendor outage on an internal HIGH service: 0.75 x 0.6 x 0.75 = 33.75, shown as 33.8.
        // Raw-weight headroom for exposure would be 22.5 but for severity 11.25 — neither adds up to
        // what re-scoring shows, so headroom is taken from the rounded scores instead.
        SeverityInput severity = scorer.severityFromLabel("major");
        ScoreBreakdown breakdown = scorer.breakdown(
                severity.factor(), ServiceExposure.INTERNAL, BusinessCriticality.HIGH, CONFIRMED);

        assertThat(scorer.score(breakdown)).isEqualTo(33.8);
        List<ScoreFactor> factors = explainer.factors(severity, breakdown);

        // Maxed alone: severity -> 45.0, exposure -> 56.3, criticality -> 45.0.
        assertThat(factors).extracting(ScoreFactor::headroom).containsExactly(11.2, 22.5, 11.2, 0.0);
        assertThat(factors).extracting(ScoreFactor::limiting).containsExactly(false, true, false, false);
        assertThat(factor(factors, SEVERITY).setting()).isEqualTo("aegis.scoring.severity-label.major");
    }

    @Test
    void namesNothingAsLimitingWhenTheScoreIsAlreadyTheWorstCase() {
        SeverityInput severity = scorer.severityFromCvss("3.1", new BigDecimal("10.0"));
        ScoreBreakdown breakdown = scorer.breakdown(
                severity.factor(), ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL, CONFIRMED);

        List<ScoreFactor> factors = explainer.factors(severity, breakdown);

        assertThat(factors).extracting(ScoreFactor::headroom).containsOnly(0.0);
        assertThat(factors).noneMatch(ScoreFactor::limiting);
        // Nothing to blame, so the sentence stops at the arithmetic.
        assertThat(explainer.explain("CVE-2099-1", "Payments", 100.0, ImpactTier.CRITICAL, breakdown, factors))
                .isEqualTo("CVE-2099-1 scores 100.0 (CRITICAL) for Payments: severity 1.00 (CVSS 3.1 base 10) "
                        + "x exposure 1.00 (INTERNET_FACING) x criticality 1.00 (CRITICAL)");
    }

    @Test
    void flagsEveryFactorTiedForHoldingTheScoreDownMost() {
        SeverityInput severity = scorer.severityFromCvss("3.1", new BigDecimal("10.0"));
        ScoreBreakdown breakdown = new ScoreBreakdown(
                1.0, ServiceExposure.INTERNAL, 0.5, BusinessCriticality.MEDIUM, 0.5, CONFIRMED, 1.0);

        List<ScoreFactor> factors = explainer.factors(severity, breakdown);

        assertThat(factors).extracting(ScoreFactor::limiting).containsExactly(false, true, true, false);
        assertThat(explainer.explain("CVE-2099-1", "Orders", 25.0, ImpactTier.MEDIUM, breakdown, factors))
                .endsWith("; exposure (INTERNAL) and criticality (MEDIUM) hold it down most, "
                        + "costing 25.0 points each");
    }

    @Test
    void aZeroFactorIsTheOnlyOneWithHeadroom() {
        // CVSS 0.0: nothing about the service can raise the score, so only severity is to blame.
        SeverityInput severity = scorer.severityFromCvss("3.1", new BigDecimal("0.0"));
        ScoreBreakdown breakdown = scorer.breakdown(
                severity.factor(), ServiceExposure.INTERNAL, BusinessCriticality.HIGH, CONFIRMED);

        List<ScoreFactor> factors = explainer.factors(severity, breakdown);

        assertThat(factors).extracting(ScoreFactor::headroom).containsExactly(45.0, 0.0, 0.0, 0.0);
        assertThat(factors).extracting(ScoreFactor::limiting).containsExactly(true, false, false, false);
    }

    @Test
    void pointsASeverityWordAtItsLabelSettingAndAMissingOneAtTheFallback() {
        ScoreFactor labelled = severityFactor(scorer.severityFromLabel("Major"));
        assertThat(labelled.input()).isEqualTo("severity Major");
        assertThat(labelled.weight()).isEqualTo(0.75);
        assertThat(labelled.setting()).isEqualTo("aegis.scoring.severity-label.major");

        ScoreFactor missing = severityFactor(scorer.severityFromLabel(null));
        assertThat(missing.input()).isEqualTo("no published severity");
        assertThat(missing.weight()).isEqualTo(0.5);
        assertThat(missing.setting()).isEqualTo("aegis.scoring.unknown-severity-factor");

        ScoreFactor unrecognised = severityFactor(scorer.severityFromLabel("sev9"));
        assertThat(unrecognised.input()).isEqualTo("unrecognised severity 'sev9'");
        assertThat(unrecognised.setting()).isEqualTo("aegis.scoring.unknown-severity-factor");
    }

    @Test
    void spellsOutAConfiguredConfidenceDiscountAndCanBlameIt() {
        ImpactScorer strict = new ImpactScorer(new ScoringProperties(
                null, null, Map.of(POSSIBLE, 0.5), null, null, null, null, null));
        ScoreExplainer explaining = new ScoreExplainer(strict);
        SeverityInput severity = strict.severityFromCvss("3.1", new BigDecimal("10.0"));
        ScoreBreakdown breakdown = strict.breakdown(
                severity.factor(), ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL, POSSIBLE);

        List<ScoreFactor> factors = explaining.factors(severity, breakdown);

        assertThat(factor(factors, CONFIDENCE).setting()).isEqualTo("aegis.scoring.confidence.possible");
        assertThat(explaining.explain("CVE-2099-1", "Payments", 50.0, ImpactTier.HIGH, breakdown, factors))
                .endsWith("x criticality 1.00 (CRITICAL) x confidence 0.50 (POSSIBLE); "
                        + "confidence (POSSIBLE) holds it down most, costing 50.0 points");
    }

    @Test
    void saysSoWhenAServiceDeclaredNoExposureOrCriticality() {
        SeverityInput severity = scorer.severityFromCvss("3.1", new BigDecimal("10.0"));
        ScoreBreakdown breakdown = scorer.breakdown(severity.factor(), null, null, CONFIRMED);

        List<ScoreFactor> factors = explainer.factors(severity, breakdown);

        // An undeclared exposure is scored as the default, and says which setting that default is.
        assertThat(factor(factors, EXPOSURE).input()).isEqualTo("INTERNAL (default)");
        assertThat(factor(factors, EXPOSURE).setting()).isEqualTo("aegis.scoring.exposure.internal");
        // An undeclared criticality falls back to a built-in weight no setting controls.
        assertThat(factor(factors, CRITICALITY).input()).isEqualTo("unset");
        assertThat(factor(factors, CRITICALITY).setting()).isNull();
    }

    private ScoreFactor severityFactor(SeverityInput severity) {
        ScoreBreakdown breakdown = scorer.breakdown(
                severity.factor(), ServiceExposure.INTERNET_FACING, BusinessCriticality.CRITICAL, CONFIRMED);
        return factor(explainer.factors(severity, breakdown), SEVERITY);
    }

    private static ScoreFactor factor(List<ScoreFactor> factors, ScoreFactorKind kind) {
        return factors.stream().filter(factor -> factor.factor() == kind).findFirst().orElseThrow();
    }

    private static ScoreBreakdown maximised(ScoreBreakdown b, ScoreFactorKind kind) {
        return new ScoreBreakdown(
                kind == SEVERITY ? 1.0 : b.severityFactor(),
                b.exposure(),
                kind == EXPOSURE ? 1.0 : b.exposureFactor(),
                b.criticality(),
                kind == CRITICALITY ? 1.0 : b.criticalityFactor(),
                b.confidence(),
                kind == CONFIDENCE ? 1.0 : b.confidenceFactor());
    }
}
