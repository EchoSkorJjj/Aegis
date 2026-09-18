package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
import com.aegis.core.scoring.ScoringDtos.SeveritySource;
import org.springframework.stereotype.Component;

/**
 * The impact arithmetic, and nothing else — no persistence, no traversal, no I/O. Pure and total, so
 * the same inputs always produce the same score and the whole model can be unit-tested without a
 * database or a feed.
 *
 * <p>{@code score = severity x exposure x criticality x 100}: how bad the vulnerability is, how
 * reachable this particular service is, and how much this organization cares if it falls over. Each
 * term is a multiplier in {@code [0,1]}, so the result is a 0-100 percentage of the worst case, and a
 * service that is weak on any one axis cannot be pushed to the top by the other two.
 *
 * <p>Nothing here interprets advisory prose or calls a model. Severity is the number the feed already
 * published; everything else is an attribute the owner declared on the service.
 */
@Component
public class ImpactScorer {

    private final ScoringWeights weights;

    public ImpactScorer(ScoringWeights weights) {
        this.weights = weights;
    }

    public ScoringWeights weights() {
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
        double factor = clamp(baseScore.doubleValue() / 10.0);
        return new SeverityInput(SeveritySource.CVSS, cvssVersion, baseScore, null, factor);
    }

    /**
     * The severity term for a signal with no CVSS — a vendor outage, or a CVE still awaiting analysis.
     * Feeds spell severity differently (NVD's {@code CRITICAL/HIGH/MEDIUM/LOW}, Statuspage's
     * {@code critical/major/minor/none}), so both vocabularies are recognised. An unrecognised or
     * absent label falls back to the neutral {@code unknownSeverityFactor} rather than to zero: not
     * knowing how bad something is must not rank it as harmless.
     */
    public SeverityInput severityFromLabel(String label) {
        if (label == null || label.isBlank()) {
            return new SeverityInput(
                    SeveritySource.NONE, null, null, null, clamp(weights.unknownSeverityFactor()));
        }
        Double factor = labelFactor(label.trim().toLowerCase(Locale.ROOT));
        return factor == null
                ? new SeverityInput(SeveritySource.NONE, null, null, label, clamp(weights.unknownSeverityFactor()))
                : new SeverityInput(SeveritySource.SEVERITY_LABEL, null, null, label, factor);
    }

    private static Double labelFactor(String label) {
        return switch (label) {
            case "critical" -> 0.95;
            case "high", "major", "important" -> 0.75;
            case "medium", "moderate", "minor" -> 0.5;
            case "low" -> 0.25;
            case "none", "maintenance", "informational" -> 0.1;
            default -> null;
        };
    }

    /** The per-service breakdown: the three factors and the inputs they were read from. */
    public ScoreBreakdown breakdown(
            double severityFactor, ServiceExposure exposure, BusinessCriticality criticality) {
        return new ScoreBreakdown(
                clamp(severityFactor),
                exposure,
                weights.exposureFactor(exposure),
                criticality,
                weights.criticalityFactor(criticality));
    }

    /** The 0-100 score for a breakdown, rounded to one decimal so it is stable to display and compare. */
    public double score(ScoreBreakdown breakdown) {
        double product = breakdown.severityFactor() * breakdown.exposureFactor() * breakdown.criticalityFactor();
        return round(clamp(product) * 100.0);
    }

    public ImpactTier tier(double score) {
        ScoringWeights.Tiers tiers = weights.tiers();
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

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
