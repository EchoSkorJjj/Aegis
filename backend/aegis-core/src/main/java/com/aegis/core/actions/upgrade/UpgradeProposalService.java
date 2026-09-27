package com.aegis.core.actions.upgrade;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.actions.pr.FileChange;
import com.aegis.core.actions.pr.PrProvider;
import com.aegis.core.actions.pr.PrProviders;
import com.aegis.core.actions.pr.PullRequestDraft;
import com.aegis.core.actions.pr.PullRequestRef;
import com.aegis.core.actions.pr.RepositoryFile;
import com.aegis.core.actions.pr.RepositoryRef;
import com.aegis.core.actions.upgrade.UpgradeProposal.Edit;
import com.aegis.core.actions.upgrade.UpgradePullRequestText.Facts;
import com.aegis.core.actions.upgrade.UpgradePullRequestText.Rationale;
import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.ai.AiDrafter;
import com.aegis.core.ai.AiDtos;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.sbom.SbomGenerationProperties;
import com.aegis.core.matching.MatchingDtos.ChangeRef;
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.matching.MatchingDtos.EvidenceChain;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.UpgradeTargets;
import com.aegis.core.matching.UpgradeTargets.UpgradeTarget;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.scoring.ScoringService;
import com.aegis.core.signals.ChangeSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Safe-upgrade draft pull requests (E8 story 4) with the decision step they need (an early slice of E9).
 * Each is part of a response (E8 story 5): drafted with the risk ticket for the same vulnerability and
 * service, and approved with it.
 *
 * <p><b>Plan</b> and <b>draft</b> turn "this CVE reaches this service" into one reviewed pull request: every
 * direct dependency it matches, in every manifest that declares it, moved to the safe version (deterministic,
 * {@link UpgradeTargets}), with the exact PR title and body and an AI-drafted rationale when the ai-service can
 * supply one. Nothing is written outside Aegis. <b>Approve</b> is the trust boundary: a second person's
 * decision — never the drafter's own — and the only thing that opens a pull request, always a draft, from an
 * {@code aegis/} branch, never merged by Aegis. <b>Dismiss</b> declines with a reason; the drafter may
 * withdraw their own draft this way, since that opens nothing.
 *
 * <p>The repository is always the service's own link, never a caller's input. At approval the manifest
 * is re-read and the edit re-applied, and the write is pinned to the revision just read, so a manifest
 * that changed in between is refused rather than overwritten.
 */
@Service
public class UpgradeProposalService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeProposalService.class);

    /** An approval interrupted mid-way (a crash while opening) may be retried after this long. */
    static final Duration STALE_OPENING = Duration.ofMinutes(10);
    private static final int MAX_ADVISORY_CONTEXT = 4_000;
    private static final int MAX_FAILURE_MESSAGE = 1_000;

    private final InventoryService inventory;
    private final InventoryStore inventoryStore;
    private final ScoringService scoring;
    private final UpgradeTargets targets;
    private final AdvisoryStore advisories;
    private final AiDrafter drafter;
    private final PrProviders providers;
    private final UpgradeProposalStore store;
    private final SbomGenerationProperties repositoryHosts;
    private final PullRequestHosts pullRequestHosts;

    public UpgradeProposalService(
            InventoryService inventory,
            InventoryStore inventoryStore,
            ScoringService scoring,
            UpgradeTargets targets,
            AdvisoryStore advisories,
            AiDrafter drafter,
            PrProviders providers,
            UpgradeProposalStore store,
            SbomGenerationProperties repositoryHosts,
            PullRequestHosts pullRequestHosts) {
        this.inventory = inventory;
        this.inventoryStore = inventoryStore;
        this.scoring = scoring;
        this.targets = targets;
        this.advisories = advisories;
        this.drafter = drafter;
        this.providers = providers;
        this.store = store;
        this.repositoryHosts = repositoryHosts;
        this.pullRequestHosts = pullRequestHosts;
    }

    /**
     * What the upgrade pull request of a response to this change in this service would edit: every direct
     * dependency the change matches, in every manifest the SBOM import found it declared in (the ecosystem's
     * manifest at the repository root when it recorded none, and none at all when it recorded only files Aegis
     * doesn't edit), each moved to the lowest version that clears every
     * advisory with a fix for its package ({@link UpgradeTargets}). A dependency another current pull request
     * already upgrades is left to that one. What can't be automated — pulled in by another package, an
     * ecosystem or a manifest Aegis doesn't edit, no fixed version, no linked repository, a repository host no
     * pull-request provider serves — is listed with why.
     * Deterministic, and writes nothing.
     */
    public UpgradePlan plan(UUID changeSignalId, UUID serviceId) {
        BusinessService service = inventory.getBusinessService(serviceId);
        ChangeImpactResponse impact = scoring.scoreChange(changeSignalId);
        if (impact.change().signalType() != ChangeSignal.SignalType.VULNERABILITY) {
            return UpgradePlan.none(changeSignalId, serviceId, impact.change());
        }
        ScoredService scored = impact.services().stream()
                .filter(candidate -> candidate.service().id().equals(serviceId))
                .findFirst()
                .orElseThrow(() -> new ApiExceptions.Conflict("This change does not reach the service"));

        // Each component this change matched for the service, once; a direct use wins over a transitive one.
        Map<UUID, EvidenceChain> byComponent = new LinkedHashMap<>();
        for (EvidenceChain chain : scored.evidence()) {
            if (chain.componentPath() == null || chain.componentPath().isEmpty()) {
                continue;
            }
            byComponent.merge(chain.componentPath().getFirst().id(), chain,
                    (kept, other) -> other.componentPath().size() < kept.componentPath().size() ? other : kept);
        }
        Map<UUID, List<String>> declaredIn = new HashMap<>();
        inventoryStore.findServiceComponentLinksForServices(List.of(serviceId))
                .forEach(link -> declaredIn.put(link.componentId(), link.manifestPaths()));
        Set<String> claimed = new HashSet<>();
        store.findCurrent(serviceId).forEach(current -> current.edits()
                .forEach(edit -> claimed.add(claim(edit.componentId(), edit.manifestPath()))));

        String vulnerabilityId = null;
        List<Edit> edits = new ArrayList<>();
        List<String> notAutomated = new ArrayList<>();
        Set<String> checked = new LinkedHashSet<>();
        Set<String> notCleared = new LinkedHashSet<>();
        Map<String, ManifestEditor> editors = new LinkedHashMap<>();
        Map<String, Set<String>> edited = new LinkedHashMap<>();
        Set<String> evidence = new LinkedHashSet<>();
        MatchConfidence confidence = MatchConfidence.CONFIRMED;
        for (EvidenceChain chain : byComponent.values()) {
            ComponentRef component = chain.componentPath().getFirst();
            if (chain.componentPath().size() > 1) {
                String direct = chain.componentPath().getLast().name();
                notAutomated.add(component.name() + " " + component.version() + " comes in through " + direct
                        + "; upgrade " + direct + ", or pin " + component.name() + " by hand");
                continue;
            }
            Optional<ManifestEditor> editor = ManifestEditor.forEcosystem(component.ecosystem());
            if (editor.isEmpty()) {
                notAutomated.add("Aegis drafts upgrades for Maven, npm and PyPI manifests; upgrade " + component.name()
                        + " (" + component.ecosystem() + ") by hand");
                continue;
            }
            List<String> recorded = declaredIn.getOrDefault(component.id(), List.of());
            List<String> manifests = editor.get().manifestsFor(recorded);
            if (manifests.isEmpty()) {
                notAutomated.add(component.name() + " " + component.version() + " is found in "
                        + String.join(", ", recorded) + ", which Aegis doesn't edit; upgrade it by hand");
                continue;
            }
            UpgradeTarget target;
            try {
                target = targets.resolve(changeSignalId, component);
            } catch (ApiExceptions.Conflict unresolved) {
                notAutomated.add(component.name() + " " + component.version() + ": " + unresolved.getMessage());
                continue;
            }
            vulnerabilityId = target.vulnerabilityId();
            boolean added = false;
            for (String manifest : manifests) {
                if (claimed.contains(claim(component.id(), manifest))) {
                    notAutomated.add(component.name() + " in " + manifest
                            + " is already being upgraded by another upgrade pull request");
                    continue;
                }
                edits.add(new Edit(component.id(), component.ecosystem(), component.name(), component.version(),
                        target.toVersion(), manifest));
                editors.putIfAbsent(editor.get().fileName(), editor.get());
                edited.computeIfAbsent(editor.get().fileName(), file -> new LinkedHashSet<>()).add(manifest);
                added = true;
            }
            if (added) {
                checked.addAll(target.checked());
                notCleared.addAll(target.notCleared());
                List<String> elsewhere = recorded.stream().map(String::strip)
                        .filter(path -> !manifests.contains(path)).distinct().toList();
                if (!elsewhere.isEmpty()) {
                    notAutomated.add(component.name() + " " + component.version() + " is also found in "
                            + String.join(", ", elsewhere) + ", which Aegis doesn't edit; upgrade it there by hand");
                }
                if (chain.explanation() != null && !chain.explanation().isBlank()) {
                    evidence.add(chain.explanation());
                }
                if (chain.confidence() != MatchConfidence.CONFIRMED) {
                    confidence = MatchConfidence.POSSIBLE;
                }
            }
        }
        List<String> followUps = new ArrayList<>();
        edited.forEach((file, manifests) -> followUps.addAll(editors.get(file).followUps(List.copyOf(manifests))));
        if (!edits.isEmpty() && service.getRepositoryUrl() == null) {
            notAutomated.addFirst("Link the service's repository so Aegis can draft the upgrade pull request for "
                    + edits.stream().map(Edit::packageName).distinct().collect(Collectors.joining(", ")));
            edits.clear();
        } else if (!edits.isEmpty()) {
            String unsupported = pullRequestHosts.unsupported(service.getRepositoryUrl());
            if (unsupported != null) {
                notAutomated.addFirst(unsupported + "; upgrade "
                        + edits.stream().map(Edit::packageName).distinct().collect(Collectors.joining(", ")) + " by hand");
                edits.clear();
            }
        }
        return new UpgradePlan(changeSignalId, serviceId, impact.change(),
                vulnerabilityId == null ? impact.change().externalId() : vulnerabilityId, edits, notAutomated,
                new UpgradePlan.Facts(service.getName(), service.getCriticality().name(),
                        service.getExposure().name(), scored.score(), scored.tier().name(), service.getRepositoryUrl(),
                        service.getRepositoryBranch(), confidence, List.copyOf(checked), List.copyOf(notCleared),
                        List.copyOf(evidence), List.copyOf(followUps)));
    }

    /**
     * Draft the pull request a plan describes, as part of the response (risk ticket) {@code riskTicketId}: the
     * exact title and body, with an AI-drafted rationale when the ai-service can supply one. Writes nothing
     * outside Aegis. Empty when the plan has nothing Aegis can automate.
     */
    public Optional<UpgradeProposal> draft(UpgradePlan plan, UUID riskTicketId, UUID drafter) {
        if (!plan.automatable()) {
            return Optional.empty();
        }
        UpgradePlan.Facts planned = plan.facts();
        UUID id = UUID.randomUUID();
        Facts facts = new Facts(id, plan.vulnerabilityId(), planned.checked(), planned.notCleared(),
                planned.serviceName(), planned.criticality(), planned.exposure(), planned.score(), planned.tier(),
                plan.edits(), planned.confidence().name(), String.join(" ", planned.evidence()), planned.followUps());
        String body = UpgradePullRequestText.body(facts, rationale(id, plan.change(), facts));
        Edit first = plan.edits().getFirst();
        return Optional.of(store.create(new UpgradeProposal(
                id, plan.serviceId(), plan.changeSignalId(), plan.vulnerabilityId(), riskTicketId, plan.edits(),
                planned.repositoryUrl(), planned.baseBranch(), headBranch(first.packageName(), first.toVersion(), id),
                planned.confidence(), UpgradePullRequestText.title(plan.edits(), plan.vulnerabilityId()), body,
                UpgradeProposalStatus.PROPOSED, drafter, null, null, null, null, null, null, null, 0)));
    }

    /**
     * A person's approval of a proposal drafted on its own, before responses: open its draft pull request.
     * A response's pull request is decided on the response instead ({@link #approveForResponse}).
     */
    public UpgradeProposal approve(UUID proposalId, UUID approver) {
        UpgradeProposal proposal = get(proposalId);
        if (proposal.riskTicketId() != null) {
            throw new ApiExceptions.Conflict("This pull request is part of a response; decide it on the response");
        }
        return open(proposal, approver, null);
    }

    /**
     * A person's approval of a response's pull request, once its risk ticket is filed: open it with the
     * ticket's key in front of its title, so the tracker links the two.
     */
    public UpgradeProposal approveForResponse(UUID proposalId, UUID riskTicketId, UUID approver, String issueKey) {
        UpgradeProposal proposal = get(proposalId);
        if (!riskTicketId.equals(proposal.riskTicketId())) {
            throw new ApiExceptions.NotFound("Upgrade proposal not found in this response: " + proposalId);
        }
        return open(proposal, approver, issueKey);
    }

    /**
     * Open the draft pull request. The approver is never the drafter — retrying a failed opening is approving
     * too. The proposal is claimed first (so a second, concurrent approval gets a conflict, not a second pull
     * request); a failure is recorded on the proposal, which can then be approved again or dismissed, and is
     * rethrown to the approver.
     */
    private UpgradeProposal open(UpgradeProposal proposal, UUID approver, String issueKey) {
        if (proposal.createdBy().equals(approver)) {
            throw new ApiExceptions.Forbidden("You drafted this proposal, so someone else has to approve it");
        }
        Instant now = Instant.now();
        boolean interrupted = proposal.status() == UpgradeProposalStatus.OPENING && proposal.decidedAt() != null
                && proposal.decidedAt().isBefore(now.minus(STALE_OPENING));
        if (proposal.status() != UpgradeProposalStatus.PROPOSED && proposal.status() != UpgradeProposalStatus.FAILED
                && !interrupted) {
            throw new ApiExceptions.Conflict("An upgrade proposal that is "
                    + proposal.status().name().toLowerCase(Locale.ROOT) + " cannot be approved");
        }
        BusinessService service = inventory.getBusinessService(proposal.serviceId());
        if (!Objects.equals(service.getRepositoryUrl(), proposal.repositoryUrl())
                || !Objects.equals(service.getRepositoryBranch(), proposal.baseBranch())) {
            throw new ApiExceptions.Conflict("The service's repository link changed after this proposal was drafted; "
                    + "dismiss it and draft a new one");
        }
        String title = issueKey == null ? proposal.title() : UpgradePullRequestText.titled(issueKey, proposal.title());
        UpgradeProposal opening = store.update(proposal.opening(approver, now, title));
        try {
            return store.update(opening.opened(openDraftPullRequest(opening, issueKey)));
        } catch (RuntimeException failure) {
            try {
                store.update(opening.failed(failureMessage(failure)));
            } catch (RuntimeException recording) {
                failure.addSuppressed(recording);
            }
            throw failure;
        }
    }

    public UpgradeProposal dismiss(UUID proposalId, UUID by, String reason) {
        UpgradeProposal proposal = get(proposalId);
        if (proposal.status() != UpgradeProposalStatus.PROPOSED && proposal.status() != UpgradeProposalStatus.FAILED) {
            throw new ApiExceptions.Conflict("An upgrade proposal that is "
                    + proposal.status().name().toLowerCase(Locale.ROOT) + " cannot be dismissed");
        }
        return store.update(proposal.dismissed(by, Instant.now(), reason.trim()));
    }

    public UpgradeProposal get(UUID proposalId) {
        return store.find(proposalId)
                .orElseThrow(() -> new ApiExceptions.NotFound("Upgrade proposal not found: " + proposalId));
    }

    public Page<UpgradeProposal> list(UUID serviceId, UUID changeSignalId, Collection<UpgradeProposalStatus> statuses,
            boolean standaloneOnly, Pageable pageable) {
        return store.findPage(serviceId, changeSignalId, statuses, standaloneOnly, pageable);
    }

    /** Every pull request of these responses, newest first. */
    public List<UpgradeProposal> forTickets(Collection<UUID> riskTicketIds) {
        return store.findForTickets(riskTicketIds);
    }

    /** The current names of these services (org-scoped), for display next to their proposals. */
    public Map<UUID, String> serviceNames(Collection<UUID> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        inventoryStore.findBusinessServicesByIds(serviceIds).forEach((id, service) -> names.put(id, service.getName()));
        return names;
    }

    /**
     * Re-read every manifest the proposal edits, apply its edits to each (several in one file in turn), and open
     * the draft pull request with each write pinned to the revision just read.
     */
    private PullRequestRef openDraftPullRequest(UpgradeProposal proposal, String issueKey) {
        RepositoryUrl url = RepositoryUrl.parse(proposal.repositoryUrl(), repositoryHosts.allowedHosts());
        RepositoryRef repository = new RepositoryRef(url.host(), url.path());
        PrProvider provider = providers.forRepository(repository);
        Map<String, List<Edit>> byManifest = new LinkedHashMap<>();
        proposal.edits().forEach(edit -> byManifest.computeIfAbsent(edit.manifestPath(), path -> new ArrayList<>())
                .add(edit));
        List<FileChange> changes = new ArrayList<>();
        for (Map.Entry<String, List<Edit>> manifest : byManifest.entrySet()) {
            RepositoryFile file = provider.readFile(repository, proposal.baseBranch(), manifest.getKey());
            String content = file.content();
            boolean edited = false;
            Set<String> moved = new HashSet<>();
            for (Edit edit : manifest.getValue()) {
                ManifestEditor editor = ManifestEditor.forEcosystem(edit.ecosystem())
                        .orElseThrow(() -> new ApiExceptions.Conflict("No manifest editor for " + edit.ecosystem()));
                if (!moved.add(edit.packageName() + " " + edit.toVersion())) {
                    continue; // two versions of one package in one file: the first edit moved every declaration
                }
                try {
                    content = editor.upgrade(content, edit.packageName(), edit.fromVersion(), edit.toVersion());
                    edited = true;
                } catch (ApiExceptions.Conflict refused) {
                    // Two packages sharing one version property: the first edit already moved it.
                    if (!edited || !alreadyAt(editor, content, edit)) {
                        throw refused;
                    }
                }
            }
            changes.add(new FileChange(manifest.getKey(), content, file.revision()));
        }
        String body = issueKey == null ? proposal.body() : UpgradePullRequestText.tracking(issueKey, proposal.body());
        return provider.openDraftPullRequest(new PullRequestDraft(repository, proposal.baseBranch(),
                proposal.headBranch(), proposal.title(), body, proposal.title(), changes));
    }

    /** Whether the manifest already declares the edit's package at its target version. */
    private static boolean alreadyAt(ManifestEditor editor, String content, Edit edit) {
        try {
            editor.upgrade(content, edit.packageName(), edit.toVersion(), edit.toVersion());
            return true;
        } catch (ApiExceptions.Conflict notThere) {
            return false;
        }
    }

    private Rationale rationale(UUID proposalId, ChangeRef change, Facts facts) {
        try {
            AiDtos.Draft draft = drafter.draft(new AiDtos.DraftRequest(proposalId.toString(), "upgrade", context(change, facts)));
            return Rationale.drafted(draft.content(), draft.citations());
        } catch (RuntimeException unavailable) {
            // The deterministic facts stand on their own: an AI outage must never block a fix.
            log.warn("upgrade proposal {}: no AI rationale ({})", proposalId, unavailable.getClass().getSimpleName());
            return Rationale.unavailable("the AI service could not draft one");
        }
    }

    /** The ai-service's grounding: the advisory's own text and the deterministic facts, nothing else. */
    private String context(ChangeRef change, Facts facts) {
        String description = advisories.findByExternalId(change.sourceId(), change.externalId())
                .map(Advisory::description)
                .filter(text -> text != null && !text.isBlank())
                .map(text -> text.length() <= MAX_ADVISORY_CONTEXT ? text : text.substring(0, MAX_ADVISORY_CONTEXT))
                .orElse("(the advisory publishes no description)");
        String upgrades = facts.edits().stream()
                .map(edit -> edit.packageName() + " " + edit.fromVersion() + " to " + edit.toVersion() + " in "
                        + edit.manifestPath())
                .collect(Collectors.joining("; "));
        return """
                Vulnerability %s: %s
                Proposed upgrades: %s. Each target is the lowest version outside every published affected range \
                with a fix (checked against %s).
                Service: %s (%s criticality, %s), impact score %s (%s).
                Evidence: %s
                """.formatted(facts.vulnerabilityId(), description, upgrades, String.join(", ", facts.checked()),
                facts.serviceName(), facts.criticality(), facts.exposure(), facts.score(), facts.tier(),
                facts.evidence());
    }

    /**
     * The manifests to edit: those the SBOM import recorded that fit this editor, else the ecosystem's
     * manifest at the repository root.
     */
    private static String claim(UUID componentId, String manifestPath) {
        return componentId + " " + manifestPath;
    }

    /** {@code aegis/upgrade-log4j-core-2.17.1-1a2b3c4d}: readable, unique per proposal, a safe Git ref. */
    static String headBranch(String packageName, String toVersion, UUID proposalId) {
        String leaf = packageName.substring(Math.max(packageName.lastIndexOf(':'), packageName.lastIndexOf('/')) + 1);
        String slug = (leaf + "-" + toVersion)
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("\\.{2,}", ".")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[^A-Za-z0-9]+", "");
        if (slug.length() > 80) {
            slug = slug.substring(0, 80);
        }
        return "aegis/upgrade-" + slug + "-" + proposalId.toString().substring(0, 8);
    }

    /** What the proposal records about a failure: an API error's own (safe) message, else a generic one. */
    private static String failureMessage(RuntimeException failure) {
        String message = failure instanceof ApiExceptions.NotFound || failure instanceof ApiExceptions.Conflict
                || failure instanceof ApiExceptions.BadRequest || failure instanceof ApiExceptions.Forbidden
                || failure instanceof ApiExceptions.UpstreamUnavailable
                ? failure.getMessage()
                : "Opening the draft pull request failed";
        return message.length() <= MAX_FAILURE_MESSAGE ? message : message.substring(0, MAX_FAILURE_MESSAGE);
    }
}
