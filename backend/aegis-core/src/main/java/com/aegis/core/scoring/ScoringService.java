package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.matching.MatchingDtos.ChangeMatchResponse;
import com.aegis.core.matching.MatchingDtos.ChangeRef;
import com.aegis.core.matching.MatchingDtos.ImpactedServiceMatch;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.matching.MatchingService;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.EstateRollupResponse;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.OwnerRollup;
import com.aegis.core.scoring.ScoringDtos.RollupWindow;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.scoring.ScoringDtos.ScoreFactor;
import com.aegis.core.scoring.ScoringDtos.ScoredOwner;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.scoring.ScoringDtos.ServiceRollup;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignalFilter;
import com.aegis.core.signals.ChangeSignalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    /**
     * Which of several feeds' CVSS scores for one vulnerability to report: the highest base score;
     * on a tie, the newest CVSS generation (3.1 over 3.0 over 2.0); then the feed and its identifier
     * in alphabetical order — arbitrary, but the same answer every time.
     */
    private static final Comparator<Advisory> STRONGEST_CVSS = Comparator
            .comparing((Advisory advisory) -> advisory.cvss().baseScore())
            .thenComparing(advisory -> cvssGeneration(advisory.cvss().version()))
            .thenComparing(Advisory::sourceId, Comparator.reverseOrder())
            .thenComparing(Advisory::externalId, Comparator.reverseOrder());

    /** Where the newest window of signals comes first, with the id breaking ties on the same instant. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.asc("id"));

    private final MatchingService matching;
    private final ChangeSignalStore signals;
    private final AdvisoryStore advisories;
    private final ImpactScorer scorer;
    private final ScoreExplainer explainer;

    public ScoringService(
            MatchingService matching,
            ChangeSignalStore signals,
            AdvisoryStore advisories,
            ImpactScorer scorer) {
        this.matching = matching;
        this.signals = signals;
        this.advisories = advisories;
        this.scorer = scorer;
        this.explainer = new ScoreExplainer(scorer);
    }

    @Transactional(readOnly = true)
    public ChangeImpactResponse scoreChange(UUID signalId) {
        // Matching resolves the signal itself and is the one place that rejects an unmatchable
        // (security-news) signal, so it runs first and its 404/400 is the contract for a bad id.
        ChangeMatchResponse match = matching.matchChange(signalId);
        ChangeSignal signal = signals.findById(signalId)
                .orElseThrow(() -> new ApiExceptions.NotFound("Change signal not found: " + signalId));
        return score(signal, match);
    }

    private ChangeImpactResponse score(ChangeSignal signal, ChangeMatchResponse match) {
        SeverityInput severity = resolveSeverity(signal);
        List<ScoredService> services = match.impactedServices().stream()
                .map(impacted -> scoreService(match, severity, impacted))
                .sorted(Comparator.comparingDouble(ScoredService::score).reversed()
                        .thenComparing(scored -> scored.service().name(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(scored -> scored.service().id()))
                .toList();

        return new ChangeImpactResponse(match.change(), match.matchedComponentCount(), match.matchedVendorCount(),
                severity, services, rollUpByOwner(services, match.change()));
    }


    /**
     * Score every signal in a window and aggregate the results per service and per owner — the
     * "what is on our plate" view, as opposed to {@link #scoreChange} 's "who does this CVE hurt".
     *
     * <p>Cost is {@code O(signals x match)}: every signal drives its own match and graph traversal and
     * severity lookup, with no cross-signal batching, so the work is deliberately bounded twice — by
     * the caller's window and by {@code aegis.scoring.rollup.max-signals}. When the window holds more
     * signals than the bound allows, the newest are scored and the response says {@code truncated} — a
     * roll-up that quietly covered an arbitrary subset would be worse than one that admits it. (Batching
     * the per-signal work is a deliberate later optimisation; the bound keeps it safe until then.)
     *
     * <p>No single signal fails the whole roll-up. A security-news signal carries no package or vendor
     * identity to match and is skipped before it is counted; any other signal whose advisory or match
     * cannot be resolved is logged, counted as {@code signalsSkipped}, and stepped over.
     *
     * <p>Deliberately <em>not</em> {@code @Transactional}: each signal's match runs in matching's own
     * transaction, so one that throws rolls back alone. Wrapped in a shared transaction, that same
     * exception marks the whole transaction rollback-only even though it is caught here, and the
     * roll-up then failed at commit with {@code UnexpectedRollbackException} — the skip above never
     * actually worked (CS203-74, {@code ScoringRollupResilienceIntegrationTest}). Nothing is lost: under
     * Postgres's READ COMMITTED every statement reads the latest committed data anyway, so the outer
     * transaction never gave the roll-up a single snapshot to begin with.
     */
    public EstateRollupResponse rollUpEstate(ChangeSignalFilter filter, Integer requestedLimit) {
        int maxSignals = scorer.weights().rollup().maxSignals();
        int limit = requestedLimit == null ? maxSignals : Math.clamp(requestedLimit, 1, maxSignals);
        Instant from = filter.from() != null
                ? filter.from()
                : Instant.now().minus(scorer.weights().rollup().defaultWindow());
        ChangeSignalFilter windowed = new ChangeSignalFilter(
                filter.type(), filter.severity(), filter.source(), filter.search(), filter.ecosystem(),
                from, filter.to());

        // One extra row is fetched purely to tell "exactly at the bound" from "there was more". The page
        // must be explicitly newest-first: unsorted, Postgres returns the change_signals view in no
        // particular order, and a truncated roll-up would cover an arbitrary subset (CS203-74).
        List<ChangeSignal> page = signals.findPage(windowed, PageRequest.of(0, limit + 1, NEWEST_FIRST))
                .getContent();
        boolean truncated = page.size() > limit;
        List<ChangeSignal> scored = truncated ? page.subList(0, limit) : page;

        Map<UUID, ServiceAggregate> services = new LinkedHashMap<>();
        Map<UUID, OwnerAggregate> owners = new LinkedHashMap<>();
        int considered = 0;
        int impacting = 0;
        int skipped = 0;
        for (ChangeSignal signal : scored) {
            if (signal.signalType() == ChangeSignal.SignalType.SECURITY_NEWS) {
                continue;
            }
            considered++;
            ChangeImpactResponse impact;
            try {
                impact = score(signal, matching.matchChange(signal.id()));
            } catch (RuntimeException ex) {
                // One signal whose advisory or match cannot be resolved must not sink the whole
                // roll-up — step over it, count it, and let the rest score. Same resilience the
                // security-news skip gives, extended to any single-signal failure.
                skipped++;
                log.warn("scoring roll-up: skipping signal {} ({}): {}",
                        signal.id(), signal.externalId(), ex.toString());
                continue;
            }
            if (impact.services().isEmpty()) {
                continue;
            }
            impacting++;
            for (ScoredService service : impact.services()) {
                services.computeIfAbsent(service.service().id(), ignored -> new ServiceAggregate(service.service()))
                        .add(service, impact.change());
                for (OwnerRef owner : service.owners()) {
                    owners.computeIfAbsent(owner.id(), ignored -> new OwnerAggregate(owner))
                            .add(service, impact.change());
                }
            }
        }

        return new EstateRollupResponse(
                new RollupWindow(from, filter.to(), maxSignals),
                considered,
                impacting,
                skipped,
                truncated,
                services.values().stream()
                        .map(ServiceAggregate::toRollup)
                        .sorted(Comparator.comparingDouble(ServiceRollup::topScore).reversed()
                                .thenComparing(rolled -> rolled.service().name(), String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(rolled -> rolled.service().id()))
                        .toList(),
                owners.values().stream()
                        .map(OwnerAggregate::toRollup)
                        .sorted(Comparator.comparingDouble(OwnerRollup::topScore).reversed()
                                .thenComparing(rolled -> rolled.owner().name(), String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(rolled -> rolled.owner().id()))
                        .toList());
    }

    private ScoredService scoreService(
            ChangeMatchResponse match, SeverityInput severity, ImpactedServiceMatch impacted) {
        ServiceRef service = impacted.service();
        ScoreBreakdown breakdown = scorer.breakdown(match.change().signalType(),
                severity.factor(), service.exposure(), service.criticality(), impacted.confidence());
        double score = scorer.score(breakdown);
        ImpactTier tier = scorer.tier(score);
        List<ScoreFactor> factors = explainer.factors(severity, breakdown);
        return new ScoredService(
                service,
                score,
                tier,
                scorer.material(score),
                impacted.confidence(),
                breakdown,
                factors,
                explainer.explain(match.change().title(), service.name(), score, tier, breakdown, factors),
                impacted.owners(),
                impacted.evidence());
    }

    /**
     * The advisory's own severity, expanded across every feed reporting the same vulnerability: NVD
     * may carry a CVSS score for a CVE that OSV reports without one, and vice versa. The highest
     * published base score wins — under-scoring a vulnerability because one feed lagged is the
     * failure that matters.
     *
     * <p>Ties are broken explicitly (see {@link #STRONGEST_CVSS}) rather than by whichever row the
     * database returned first, so the reported CVSS version — and the explanation built on it — is the
     * same on every request (CS203-74).
     */
    private SeverityInput resolveSeverity(ChangeSignal signal) {
        if (signal.signalType() != ChangeSignal.SignalType.VULNERABILITY) {
            // A vendor outage carries the vendor's own impact vocabulary, never a CVSS score.
            return scorer.severityFromLabel(signal.severity());
        }
        Advisory representative = advisories.findByExternalId(signal.sourceId(), signal.externalId())
                .orElseThrow(() -> new ApiExceptions.NotFound("Advisory backing change signal was not found"));
        Advisory best = advisories.findByCanonicalId(representative.canonicalId()).stream()
                .filter(candidate -> candidate.cvss() != null && candidate.cvss().baseScore() != null)
                .max(STRONGEST_CVSS)
                .orElse(null);
        if (best == null) {
            // No feed has scored it yet — fall back to whatever severity label the signal carries.
            return scorer.severityFromLabel(signal.severity());
        }
        return scorer.severityFromCvss(best.cvss().version(), best.cvss().baseScore());
    }

    /** A CVSS version as a comparable number ("3.1" → 3.1); an absent or unreadable one sorts last. */
    private static BigDecimal cvssGeneration(String version) {
        try {
            return version == null ? BigDecimal.ZERO : new BigDecimal(version.trim());
        } catch (NumberFormatException unreadable) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Per-owner roll-up of one change's scored services. An owner's score is their worst service's,
     * and the counts say how much else is on their plate — see {@link ScoredOwner}. Shares the
     * {@link OwnerAggregate} "worst service" machinery with the estate roll-up so the rule lives once.
     */
    private List<ScoredOwner> rollUpByOwner(List<ScoredService> services, ChangeRef change) {
        Map<UUID, OwnerAggregate> byOwner = new LinkedHashMap<>();
        for (ScoredService scored : services) {
            for (OwnerRef owner : scored.owners()) {
                byOwner.computeIfAbsent(owner.id(), ignored -> new OwnerAggregate(owner)).add(scored, change);
            }
        }
        return byOwner.values().stream()
                .map(OwnerAggregate::toScoredOwner)
                .sorted(Comparator.comparingDouble(ScoredOwner::score).reversed()
                        .thenComparing(owner -> owner.owner().name(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(owner -> owner.owner().id()))
                .toList();
    }

    /**
     * Running aggregate over one window. {@code worst} is kept rather than a total for the same reason
     * the per-owner roll-up takes a max: twenty low-severity hits are not worse than one imminent
     * breach, and a sum would rank them that way.
     */
    private abstract static class Aggregate {
        private ScoredService worst;
        private ChangeRef worstSignal;
        private int signalCount;
        private int materialSignalCount;

        void add(ScoredService service, ChangeRef signal) {
            signalCount++;
            if (service.material()) {
                materialSignalCount++;
            }
            if (worst == null || service.score() > worst.score()) {
                worst = service;
                worstSignal = signal;
            }
        }
    }

    private static final class ServiceAggregate extends Aggregate {
        private final ServiceRef service;

        private ServiceAggregate(ServiceRef service) {
            this.service = service;
        }

        private ServiceRollup toRollup() {
            return new ServiceRollup(
                    service, super.worst.score(), super.worst.tier(),
                    super.signalCount, super.materialSignalCount, super.worstSignal,
                    super.worst.factors(), super.worst.explanation());
        }
    }

    private static final class OwnerAggregate extends Aggregate {
        private final OwnerRef owner;
        private final Set<UUID> serviceIds = new LinkedHashSet<>();

        private OwnerAggregate(OwnerRef owner) {
            this.owner = owner;
        }

        @Override
        void add(ScoredService service, ChangeRef signal) {
            super.add(service, signal);
            serviceIds.add(service.service().id());
        }

        private OwnerRollup toRollup() {
            return new OwnerRollup(
                    owner, super.worst.score(), super.worst.tier(), serviceIds.size(),
                    super.signalCount, super.materialSignalCount,
                    super.worst.service(), super.worstSignal,
                    super.worst.factors(), super.worst.explanation());
        }

        /**
         * The single-change form of the same aggregate: within one change every {@code add} is a
         * distinct service, so {@code serviceIds}/{@code materialSignalCount} are the owner's affected
         * and material <em>service</em> counts, and there is no signal dimension to report.
         */
        private ScoredOwner toScoredOwner() {
            return new ScoredOwner(
                    owner, super.worst.score(), super.worst.tier(), serviceIds.size(),
                    super.materialSignalCount, super.worst.service());
        }
    }
}
