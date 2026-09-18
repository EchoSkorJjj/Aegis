package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.common.ApiExceptions;
import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AdvisoryStore;
import com.aegis.core.ingestion.signal.ChangeSignal;
import com.aegis.core.ingestion.signal.ChangeSignalStore;
import com.aegis.core.matching.MatchingDtos.ChangeMatchResponse;
import com.aegis.core.matching.MatchingDtos.ImpactedServiceMatch;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.matching.MatchingService;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.scoring.ScoringDtos.ScoredOwner;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
import com.aegis.core.scoring.ScoringDtos.SeveritySource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for Epic 7's impact score: it takes the services matching already proved are
 * affected, applies {@link ImpactScorer} to each, and rolls the results up per owner.
 *
 * <p>Scoring adds no traversal of its own — it is matching's output multiplied by the advisory's
 * severity. Like matching it is recomputed per request and nothing is persisted: a score derived from
 * today's SBOM and today's CVSS would be a lie tomorrow, and the findings table that will freeze one
 * is a later epic.
 */
@Service
public class ScoringService {

    private final MatchingService matching;
    private final ChangeSignalStore signals;
    private final AdvisoryStore advisories;
    private final ImpactScorer scorer;

    public ScoringService(
            MatchingService matching,
            ChangeSignalStore signals,
            AdvisoryStore advisories,
            ImpactScorer scorer) {
        this.matching = matching;
        this.signals = signals;
        this.advisories = advisories;
        this.scorer = scorer;
    }

    @Transactional(readOnly = true)
    public ChangeImpactResponse scoreChange(UUID signalId) {
        // Matching resolves the signal itself and is the one place that rejects an unmatchable
        // (security-news) signal, so it runs first and its 404/400 is the contract for a bad id.
        ChangeMatchResponse match = matching.matchChange(signalId);
        ChangeSignal signal = signals.findById(signalId)
                .orElseThrow(() -> new ApiExceptions.NotFound("Change signal not found: " + signalId));

        SeverityInput severity = resolveSeverity(signal);
        List<ScoredService> services = match.impactedServices().stream()
                .map(impacted -> scoreService(match, severity, impacted))
                .sorted(Comparator.comparingDouble(ScoredService::score).reversed()
                        .thenComparing(scored -> scored.service().name(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(scored -> scored.service().id()))
                .toList();

        return new ChangeImpactResponse(match.change(), severity, services, rollUpByOwner(services));
    }

    private ScoredService scoreService(
            ChangeMatchResponse match, SeverityInput severity, ImpactedServiceMatch impacted) {
        ServiceRef service = impacted.service();
        ScoreBreakdown breakdown =
                scorer.breakdown(severity.factor(), service.exposure(), service.criticality());
        double score = scorer.score(breakdown);
        ImpactTier tier = scorer.tier(score);
        return new ScoredService(
                service,
                score,
                tier,
                scorer.material(score),
                impacted.confidence(),
                breakdown,
                explain(match, severity, service, score, tier, breakdown),
                impacted.owners());
    }

    /**
     * The advisory's own severity, expanded across every feed reporting the same vulnerability: NVD
     * may carry a CVSS score for a CVE that OSV reports without one, and vice versa. The highest
     * published base score wins — under-scoring a vulnerability because one feed lagged is the
     * failure that matters.
     */
    private SeverityInput resolveSeverity(ChangeSignal signal) {
        if (signal.signalType() != ChangeSignal.SignalType.VULNERABILITY) {
            // A vendor outage carries the vendor's own impact vocabulary, never a CVSS score.
            return scorer.severityFromLabel(signal.severity());
        }
        Advisory representative = advisories.findByExternalId(signal.sourceId(), signal.externalId())
                .orElseThrow(() -> new ApiExceptions.NotFound("Advisory backing change signal was not found"));
        Advisory best = null;
        for (Advisory candidate : advisories.findByCanonicalId(representative.canonicalId())) {
            if (candidate.cvss() == null || candidate.cvss().baseScore() == null) {
                continue;
            }
            if (best == null || candidate.cvss().baseScore().compareTo(best.cvss().baseScore()) > 0) {
                best = candidate;
            }
        }
        if (best == null) {
            // No feed has scored it yet — fall back to whatever severity label the signal carries.
            return scorer.severityFromLabel(signal.severity());
        }
        return scorer.severityFromCvss(best.cvss().version(), best.cvss().baseScore());
    }

    /**
     * Per-owner roll-up of the scored services. An owner's score is their worst service's, and the
     * counts say how much else is on their plate — see {@link ScoredOwner}.
     */
    private List<ScoredOwner> rollUpByOwner(List<ScoredService> services) {
        Map<UUID, OwnerAccumulator> byOwner = new LinkedHashMap<>();
        for (ScoredService scored : services) {
            for (OwnerRef owner : scored.owners()) {
                byOwner.computeIfAbsent(owner.id(), ignored -> new OwnerAccumulator(owner)).add(scored);
            }
        }
        return byOwner.values().stream()
                .map(OwnerAccumulator::toScoredOwner)
                .sorted(Comparator.comparingDouble(ScoredOwner::score).reversed()
                        .thenComparing(owner -> owner.owner().name(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(owner -> owner.owner().id()))
                .toList();
    }

    private String explain(
            ChangeMatchResponse match,
            SeverityInput severity,
            ServiceRef service,
            double score,
            ImpactTier tier,
            ScoreBreakdown breakdown) {
        return "%s scores %s (%s) for %s: severity %s (%s) x exposure %s (%s) x criticality %s (%s)".formatted(
                match.change().title(),
                format(score),
                tier,
                service.name(),
                factor(breakdown.severityFactor()),
                describeSeverity(severity),
                factor(breakdown.exposureFactor()),
                breakdown.exposure(),
                factor(breakdown.criticalityFactor()),
                breakdown.criticality());
    }

    private static String describeSeverity(SeverityInput severity) {
        if (severity.source() == SeveritySource.CVSS) {
            String version = severity.cvssVersion() == null ? "CVSS" : "CVSS " + severity.cvssVersion();
            return version + " base " + trim(severity.cvssBaseScore());
        }
        if (severity.source() == SeveritySource.SEVERITY_LABEL) {
            return "severity " + severity.label();
        }
        return severity.label() == null
                ? "no published severity"
                : "unrecognised severity '" + severity.label() + "'";
    }

    private static String trim(BigDecimal value) {
        return value == null ? "n/a" : value.stripTrailingZeros().toPlainString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String factor(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private final class OwnerAccumulator {
        private final OwnerRef owner;
        private final List<ScoredService> scored = new ArrayList<>();

        private OwnerAccumulator(OwnerRef owner) {
            this.owner = owner;
        }

        private void add(ScoredService value) {
            scored.add(value);
        }

        private ScoredOwner toScoredOwner() {
            ScoredService worst = scored.stream()
                    .max(Comparator.comparingDouble(ScoredService::score))
                    .orElseThrow();
            int material = (int) scored.stream().filter(ScoredService::material).count();
            return new ScoredOwner(
                    owner, worst.score(), scorer.tier(worst.score()), scored.size(), material, worst.service());
        }
    }
}
