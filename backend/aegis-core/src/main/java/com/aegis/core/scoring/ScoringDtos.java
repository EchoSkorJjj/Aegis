package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.ChangeRef;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;

/** Wire records for the deterministic impact score and its "why this number" breakdown. */
public final class ScoringDtos {

    private ScoringDtos() {
    }

    /**
     * One change signal scored against every service it reaches, plus the per-owner roll-up of those
     * scores. Services and owners are both ordered highest score first — this is a work queue.
     */
    public record ChangeImpactResponse(
            ChangeRef change,
            SeverityInput severity,
            List<ScoredService> services,
            List<ScoredOwner> owners) {
    }

    /**
     * The one severity number the whole response is built on, and where it came from, so a reviewer
     * can tell a real CVSS base score from a label the model had to interpret.
     *
     * @param source        {@code CVSS} when a feed published a base score, {@code SEVERITY_LABEL}
     *                      when only a textual severity/impact was available, {@code NONE} when the
     *                      advisory carries neither and the neutral default was applied
     * @param cvssVersion   the CVSS generation the score came from (3.1 / 3.0 / 2), null otherwise
     * @param cvssBaseScore the published base score on its native 0-10 scale, null otherwise
     * @param label         the textual severity the factor was derived from, when that is the source
     * @param factor        the resulting multiplier in {@code [0,1]}
     */
    public record SeverityInput(
            SeveritySource source, String cvssVersion, BigDecimal cvssBaseScore, String label, double factor) {
    }

    public enum SeveritySource {
        CVSS,
        SEVERITY_LABEL,
        NONE
    }

    /**
     * One service's impact score. {@code confidence} is carried through from matching rather than
     * folded into the number: it says whether the match applies at all, which is a different question
     * from how bad it is if it does. A POSSIBLE row is a review item at whatever score it earned.
     */
    public record ScoredService(
            ServiceRef service,
            double score,
            ImpactTier tier,
            boolean material,
            MatchConfidence confidence,
            ScoreBreakdown breakdown,
            String explanation,
            List<OwnerRef> owners) {
    }

    /**
     * Every term in {@code score = severity x exposure x criticality x confidence x 100}, with the
     * input each factor was read from. Reproducing the score by hand from this record is the point;
     * {@code confidenceFactor} is 1.0 unless a deployment configured a discount.
     */
    public record ScoreBreakdown(
            double severityFactor,
            ServiceExposure exposure,
            double exposureFactor,
            BusinessCriticality criticality,
            double criticalityFactor,
            MatchConfidence confidence,
            double confidenceFactor) {
    }

    /**
     * An owner's share of this change. The score is the worst of their impacted services, not the sum:
     * owning two affected services does not make either one more dangerous, and summing would rank a
     * team with many trivial hits above a team whose payment gateway is about to be exploited.
     */
    public record ScoredOwner(
            OwnerRef owner,
            double score,
            ImpactTier tier,
            int impactedServiceCount,
            int materialServiceCount,
            ServiceRef topService) {
    }


    /**
     * The estate-wide roll-up: many change signals scored and aggregated into one ranked view per
     * service and per owner. Where {@link ChangeImpactResponse} answers "who does this CVE hurt",
     * this answers "what is on our plate", which is the question an owner actually opens the app with.
     *
     * @param signalsConsidered how many signals were scored (bounded by {@code window.maxSignals})
     * @param signalsImpacting  how many of those reached at least one service — the rest matched nothing
     * @param truncated         true when the window held more signals than the bound allowed, so the
     *                          roll-up covers only the newest of them and a narrower window would be
     *                          more honest than a bigger number
     */
    public record EstateRollupResponse(
            RollupWindow window,
            int signalsConsidered,
            int signalsImpacting,
            boolean truncated,
            List<ServiceRollup> services,
            List<OwnerRollup> owners) {
    }

    /** The slice of time and work the roll-up actually covered, echoed back so the numbers are readable. */
    public record RollupWindow(Instant from, Instant to, int maxSignals) {
    }

    /**
     * One service's standing across the window. {@code topScore} is its worst single signal — the same
     * "worst, not sum" rule as the per-owner roll-up, for the same reason — and the counts say how
     * much else is queued behind it.
     */
    public record ServiceRollup(
            ServiceRef service,
            double topScore,
            ImpactTier tier,
            int signalCount,
            int materialSignalCount,
            ChangeRef topSignal) {
    }

    /** One owner's standing across the window, aggregated over every service they own. */
    public record OwnerRollup(
            OwnerRef owner,
            double topScore,
            ImpactTier tier,
            int impactedServiceCount,
            int signalCount,
            int materialSignalCount,
            ServiceRef topService,
            ChangeRef topSignal) {
    }

    /**
     * The banded form of a score, for routing and display. Bands come from
     * {@link ScoringProperties.Tiers}; the raw score remains authoritative for ordering.
     */
    public enum ImpactTier {
        NEGLIGIBLE,
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}
