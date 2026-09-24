package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
import com.aegis.core.scoring.ScoringDtos.SeveritySource;
import com.aegis.core.signals.ChangeSignal.SignalType;
import org.springframework.stereotype.Component;

/**
 * The impact arithmetic, and nothing else — no persistence, no traversal, no I/O. Pure and total, so
 * the same inputs always produce the same score and the whole model can be unit-tested without a
 * database or a feed.
 *
 * <p>{@code score = severity x exposure x criticality x confidence x 100}: how bad the vulnerability
 * is, how reachable this particular service is, how much this organization cares if it falls over, and
 * how sure we are the match applies. Each term is a multiplier in {@code [0,1]}, so the result is a
 * 0-100 percentage of the worst case, and a service that is weak on any one axis cannot be pushed to
 * the top by the others. The confidence term defaults to 1.0, so the model reads as three factors
 * until a deployment configures a discount (see {@link ScoringProperties}).
 *
 * <p>Nothing here interprets advisory prose or calls a model. Severity is the number the feed already
 * published; everything else is an attribute declared on the service (its criticality and exposure).
 */
@Component
public class ImpactScorer {

    private final ScoringProperties weights;

    public ImpactScorer(ScoringProperties weights) {
        this.weights = weights;
    }

    public ScoringProperties weights() {
        return weights;
    }

    /**
     * The severity term for an advisory that published a CVSS base score. CVSS is already a 0-10
     * severity, so this is a straight rescale to {@code [0,1]} — no re-weighting of a number that a
     * standards body has already calibrated.
     */
    public SeverityInput severityFromCvss(String cvssVersion, BigDecimal baseScore) {
        if (baseScore == null) {
            return severityFromLabel(null);
        }
        // Decimal division, not doubleValue() / 10: 9.8 / 10 in binary floating point is
        // 0.9800000000000001, which then prints in every breakdown and mis-rounds products (CS203-74).
        double factor = clamp(baseScore.movePointLeft(1).doubleValue());
        return new SeverityInput(SeveritySource.CVSS, cvssVersion, baseScore, null, factor);
    }

    /**
     * The severity term for a signal with no CVSS — a vendor outage, or a CVE still awaiting analysis.
     * Feeds spell severity differently (NVD's {@code CRITICAL/HIGH/MEDIUM/LOW}, Statuspage's
     * {@code critical/major/minor/none}), so the mapping is a configurable table
     * ({@link ScoringProperties#severityLabelFactor}) a deployment can extend for a feed that speaks a
     * third vocabulary. An unrecognised or absent label falls back to the neutral
     * {@code unknownSeverityFactor} rather than to zero: not knowing how bad something is must not rank
     * it as harmless.
     */
    public SeverityInput severityFromLabel(String label) {
        if (label == null || label.isBlank()) {
            return new SeverityInput(
                    SeveritySource.NONE, null, null, null, clamp(weights.unknownSeverityFactor()));
        }
        Double factor = weights.severityLabelFactor(label.trim().toLowerCase(Locale.ROOT));
        return factor == null
                ? new SeverityInput(SeveritySource.NONE, null, null, label, clamp(weights.unknownSeverityFactor()))
                : new SeverityInput(SeveritySource.SEVERITY_LABEL, null, null, label, factor);
    }

    /**
     * The per-service breakdown: every factor and the input it was read from. The signal type is
     * deliberately required: whether the exposure term applies depends on it.
     *
     * <p>Exposure is inbound attack reach, so it only discounts a vulnerability. A vendor outage is an
     * availability incident — the AWS region is down for an internal service exactly as it is for an
     * internet-facing one — so for any other signal type the exposure term is 1.0 and the breakdown
     * says it was not applied, rather than a quietly different number (CS203-73).
     *
     * <p>{@code confidenceFactor} is 1.0 under the shipped weighting, so confidence is reported
     * without discounting the score — see {@link ScoringProperties}. A deployment that lowers
     * {@code aegis.scoring.confidence.possible} gets the discount, and it shows up here as its own
     * term rather than hiding inside one of the other three.
     */
    public ScoreBreakdown breakdown(
            SignalType signalType,
            double severityFactor,
            ServiceExposure exposure,
            BusinessCriticality criticality,
            MatchConfidence confidence) {
        boolean exposureApplies = signalType == SignalType.VULNERABILITY;
        return new ScoreBreakdown(
                clamp(severityFactor),
                exposure,
                exposureApplies ? weights.exposureFactor(exposure) : 1.0,
                exposureApplies,
                criticality,
                weights.criticalityFactor(criticality),
                confidence,
                weights.confidenceFactor(confidence));
    }

    /**
     * The 0-100 score for a breakdown, rounded half-up to one decimal so it is stable to display and
     * compare.
     *
     * <p>The product is taken in exact decimal arithmetic over the factors as they are displayed
     * ({@code 0.98 x 0.9 x 0.8}), not in binary floating point. In doubles, 0.07 x 0.5 x 0.5 is
     * 0.017499999..., so a score that is exactly 1.75 by hand rounded to 1.7 — one in every 25 CVSS
     * combinations came out a tenth low, and at a tier bound that is a different tier (CS203-74).
     */
    public double score(ScoreBreakdown breakdown) {
        BigDecimal product = BigDecimal.valueOf(clamp(breakdown.severityFactor()))
                .multiply(BigDecimal.valueOf(clamp(breakdown.exposureFactor())))
                .multiply(BigDecimal.valueOf(clamp(breakdown.criticalityFactor())))
                .multiply(BigDecimal.valueOf(clamp(breakdown.confidenceFactor())));
        return product.movePointRight(2).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    public ImpactTier tier(double score) {
        ScoringProperties.Tiers tiers = weights.tiers();
        if (score >= tiers.critical()) {
            return ImpactTier.CRITICAL;
        }
        if (score >= tiers.high()) {
            return ImpactTier.HIGH;
        }
        if (score >= tiers.medium()) {
            return ImpactTier.MEDIUM;
        }
        if (score >= tiers.low()) {
            return ImpactTier.LOW;
        }
        return ImpactTier.NEGLIGIBLE;
    }

    /**
     * Whether a score clears the materiality threshold — the noise control. Immaterial rows are still
     * returned, and still carry their evidence: this ranks them out of the queue, it does not hide
     * them, because "we saw it and it did not matter" is itself an auditable answer.
     */
    public boolean material(double score) {
        return score >= weights.materialityThreshold();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || value < 0) {
            return 0;
        }
        return Math.min(value, 1.0);
    }
}
