package com.aegis.core.actions.upgrade;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.aegis.core.inventory.InventoryFilters.ServiceFilter;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.VulnerabilityRef;
import com.aegis.core.matching.MatchingDtos.VulnerableDependency;
import com.aegis.core.matching.UpgradeTargets;
import com.aegis.core.matching.UpgradeTargets.UpgradeTarget;
import com.aegis.core.matching.VulnerableDependencies;
import com.aegis.core.matching.VulnerableDependencies.Found;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.scoring.ImpactScorer;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.signals.ChangeSignal.SignalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * The upgrades Aegis recommends without anyone asking: every vulnerable dependency of the services the
 * caller can see, with the version that fixes it, how much it matters, whether Aegis can automate the pull
 * request, and the response already drafted for it, if any — highest impact first. Nobody has to know a CVE to find them; this is E9's queue of material
 * items, for dependency vulnerabilities. Recomputed per request from matching ({@link VulnerableDependencies},
 * {@link UpgradeTargets}) and scoring ({@link ImpactScorer}); a proposal is only drafted when a person asks.
 *
 * <p>Every lookup happens up front, in a fixed number of batched queries however many services and
 * dependencies there are ({@link VulnerableDependencies#find}); each target is then worked out in memory from
 * the advisories that search loaded — the same answer drafting it would give, without a lookup per
 * vulnerability.
 */
@Service
public class UpgradeRecommendations {

    private static final Logger log = LoggerFactory.getLogger(UpgradeRecommendations.class);

    /** Proposals that stand for a recommendation: awaiting a decision, opening, open, or failed. */
    private static final List<UpgradeProposalStatus> CURRENT = List.of(UpgradeProposalStatus.PROPOSED,
            UpgradeProposalStatus.OPENING, UpgradeProposalStatus.OPENED, UpgradeProposalStatus.FAILED);
    private static final int MAX_PROPOSALS = 1_000;

    private final InventoryStore inventory;
    private final VulnerableDependencies vulnerable;
    private final ImpactScorer scorer;
    private final UpgradeProposalStore proposals;
    private final ResponseDirectory responses;
    private final PullRequestHosts pullRequestHosts;

    public UpgradeRecommendations(InventoryStore inventory, VulnerableDependencies vulnerable, ImpactScorer scorer,
            UpgradeProposalStore proposals, ResponseDirectory responses, PullRequestHosts pullRequestHosts) {
        this.inventory = inventory;
        this.vulnerable = vulnerable;
        this.scorer = scorer;
        this.proposals = proposals;
        this.responses = responses;
        this.pullRequestHosts = pullRequestHosts;
    }

    /** Whether Aegis can draft the pull request now and, if not, what stands in the way. */
    public enum Readiness {
        /** A direct dependency with a fixed version, a supported manifest and a linked repository. */
        READY,
        /** Everything but the repository: link it and the pull request can be drafted. */
        NO_REPOSITORY,
        /** Pulled in by another dependency; upgrading that one, or pinning this one, is a person's call. */
        TRANSITIVE,
        /**
         * Aegis can't open this pull request: an ecosystem it doesn't edit, a dependency found only in files it
         * doesn't edit (a Gradle lockfile, {@code poetry.lock}), or a repository host no pull-request provider
         * serves. The reason says which.
         */
        UNSUPPORTED,
        /** No advisory publishes a version that provably fixes it. */
        NO_FIX
    }

    /** The current pull request that upgrades this dependency, if any, and the response it belongs to. */
    public record ProposalRef(UUID id, UpgradeProposalStatus status, String toVersion, String pullRequestUrl,
                              UUID riskTicketId) {
    }

    /**
     * One recommended upgrade. {@code changeSignalId} is the vulnerability a response answers — the most severe
     * one a fixed version clears, else the most severe one — and {@code toVersion} clears every other advisory
     * with a fix too. {@code response} is the response already drafted for it: the one whose pull request
     * upgrades this dependency, else the one for {@code changeSignalId} in this service.
     */
    public record Recommendation(
            UUID serviceId,
            String serviceName,
            ComponentRef component,
            ComponentRef via,
            List<String> manifestPaths,
            MatchConfidence confidence,
            List<VulnerabilityRef> vulnerabilities,
            double score,
            ImpactTier tier,
            boolean material,
            String toVersion,
            UUID changeSignalId,
            Readiness readiness,
            String reason,
            ProposalRef proposal,
            ResponseDirectory.ResponseRef response) {
    }

    /** Every service in the caller's organization, or only {@code serviceId} (404 if it isn't one). */
    public List<Recommendation> recommend(UUID serviceId) {
        Map<UUID, BusinessService> services = serviceId == null
                ? inventory.findBusinessServices(ServiceFilter.NONE, Pageable.unpaged()).stream()
                        .collect(Collectors.toMap(BusinessService::getId, Function.identity()))
                : inventory.findBusinessServicesByIds(List.of(serviceId));
        if (serviceId != null && services.isEmpty()) {
            throw new ApiExceptions.NotFound("Business service not found: " + serviceId);
        }
        return recommendFor(services);
    }

    /** The recommendations for services the caller has already loaded, e.g. the triage queue's selection. */
    public List<Recommendation> recommendFor(Map<UUID, BusinessService> services) {
        Map<String, ProposalRef> current = currentProposals();
        Map<ResponseDirectory.ServiceChange, ResponseDirectory.ResponseRef> byChange = responses.current(services.keySet());
        Map<UUID, ResponseDirectory.ResponseRef> byTicket = new HashMap<>();
        byChange.values().forEach(response -> byTicket.put(response.ticketId(), response));
        Found found = vulnerable.find(services.keySet());
        return found.dependencies().stream()
                .map(dependency -> recommendation(dependency, services.get(dependency.serviceId()), current, found,
                        byChange, byTicket))
                .sorted(Comparator.comparingDouble(Recommendation::score).reversed()
                        .thenComparing(Recommendation::readiness)
                        .thenComparing(Recommendation::serviceName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(recommendation -> recommendation.component().name()))
                .toList();
    }

    private Recommendation recommendation(VulnerableDependency dependency, BusinessService service,
            Map<String, ProposalRef> current, Found found,
            Map<ResponseDirectory.ServiceChange, ResponseDirectory.ResponseRef> byChange,
            Map<UUID, ResponseDirectory.ResponseRef> byTicket) {
        ComponentRef component = dependency.component();
        ScoreBreakdown breakdown = scorer.breakdown(SignalType.VULNERABILITY,
                severityFactor(dependency.vulnerabilities().getFirst()), service.getExposure(),
                service.getCriticality(), dependency.confidence());
        double score = scorer.score(breakdown);

        String toVersion = null;
        UUID remediated = null;
        String blocked = null;
        for (VulnerabilityRef vulnerability : dependency.vulnerabilities()) {
            if (vulnerability.changeSignalId() == null) {
                continue;
            }
            try {
                UpgradeTarget target = UpgradeTargets.resolve(vulnerability.id(), component,
                        found.known(component, vulnerability.id()));
                toVersion = target.toVersion();
                remediated = vulnerability.changeSignalId();
                break;
            } catch (ApiExceptions.Conflict unresolved) {
                if (blocked == null) {
                    blocked = unresolved.getMessage();
                }
            } catch (RuntimeException unexpected) {
                // One dependency's odd data must never take the whole list down with it.
                log.warn("upgrade recommendation for {} {}: no target ({})", component.name(), component.version(),
                        unexpected.toString());
                if (blocked == null) {
                    blocked = "Aegis could not work out a fixed version";
                }
            }
        }

        Readiness readiness;
        String reason;
        Optional<ManifestEditor> editor = ManifestEditor.forEcosystem(component.ecosystem());
        String unsupportedHost;
        if (dependency.via() != null) {
            readiness = Readiness.TRANSITIVE;
            reason = component.name() + " comes in through " + dependency.via().name() + "; upgrade "
                    + dependency.via().name() + ", or pin " + component.name()
                    + (toVersion == null ? "" : " to " + toVersion) + " by hand";
        } else if (editor.isEmpty()) {
            readiness = Readiness.UNSUPPORTED;
            reason = "Aegis drafts upgrades for Maven, npm and PyPI manifests; upgrade this "
                    + component.ecosystem() + " package by hand";
        } else if (editor.get().manifestsFor(dependency.manifestPaths()).isEmpty()) {
            readiness = Readiness.UNSUPPORTED;
            reason = "Found in " + String.join(", ", dependency.manifestPaths())
                    + ", which Aegis doesn't edit; upgrade it by hand";
        } else if (toVersion == null) {
            readiness = Readiness.NO_FIX;
            reason = blocked != null ? blocked : "No advisory publishes a fixed version to upgrade to";
        } else if (service.getRepositoryUrl() == null) {
            readiness = Readiness.NO_REPOSITORY;
            reason = "Link the service's repository so Aegis can draft the pull request";
        } else if ((unsupportedHost = pullRequestHosts.unsupported(service.getRepositoryUrl())) != null) {
            readiness = Readiness.UNSUPPORTED;
            reason = unsupportedHost + "; upgrade it by hand";
        } else {
            readiness = Readiness.READY;
            reason = null;
        }
        // Nothing to upgrade to still gets a response (a ticket alone): for the most severe vulnerability.
        UUID answered = remediated != null ? remediated : dependency.vulnerabilities().stream()
                .map(VulnerabilityRef::changeSignalId).filter(Objects::nonNull).findFirst().orElse(null);
        ProposalRef proposal = current.get(key(service.getId(), component.id()));
        ResponseDirectory.ResponseRef response = proposal != null && proposal.riskTicketId() != null
                ? byTicket.get(proposal.riskTicketId()) : null;
        if (response == null && answered != null) {
            response = byChange.get(new ResponseDirectory.ServiceChange(service.getId(), answered));
        }
        return new Recommendation(service.getId(), service.getName(), component, dependency.via(),
                dependency.manifestPaths(), dependency.confidence(), dependency.vulnerabilities(), score,
                scorer.tier(score), scorer.material(score), toVersion, answered, readiness, reason, proposal, response);
    }

    /** The severity term of the most severe vulnerability: its CVSS score, else its feed's severity word. */
    private double severityFactor(VulnerabilityRef vulnerability) {
        BigDecimal baseScore = vulnerability.cvssBaseScore();
        return baseScore != null
                ? scorer.severityFromCvss(vulnerability.cvssVersion(), baseScore).factor()
                : scorer.severityFromLabel(vulnerability.severity()).factor();
    }

    /** The newest current proposal upgrading each service's component, in any of its manifests. */
    private Map<String, ProposalRef> currentProposals() {
        Map<String, ProposalRef> byKey = new HashMap<>();
        proposals.findPage(null, null, CURRENT, false,
                        PageRequest.of(0, MAX_PROPOSALS, Sort.by(Sort.Direction.DESC, "createdAt")))
                .forEach(proposal -> proposal.edits().forEach(edit ->
                        byKey.putIfAbsent(key(proposal.serviceId(), edit.componentId()), new ProposalRef(proposal.id(),
                                proposal.status(), edit.toVersion(), proposal.pullRequestUrl(), proposal.riskTicketId()))));
        return byKey;
    }

    private static String key(UUID serviceId, UUID componentId) {
        return serviceId + ":" + componentId;
    }
}
