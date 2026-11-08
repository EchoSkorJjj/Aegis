package com.aegis.core.actions.upgrade;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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

import com.aegis.core.actions.evidence.DraftEvidence;
import com.aegis.core.actions.pr.FileChange;
import com.aegis.core.actions.pr.PrProvider;
import com.aegis.core.actions.pr.PrProviders;
import com.aegis.core.actions.pr.PullRequestDraft;
import com.aegis.core.actions.pr.PullRequestRef;
import com.aegis.core.actions.pr.RepositoryFile;
import com.aegis.core.actions.pr.RepositoryRef;
import com.aegis.core.actions.upgrade.UpgradeProposal.Edit;
import com.aegis.core.actions.upgrade.UpgradeProposal.VersionOverride;
import com.aegis.core.actions.upgrade.UpgradePullRequestText.Facts;
import com.aegis.core.actions.upgrade.UpgradePullRequestText.Rationale;
import com.aegis.core.ai.AiDrafter;
import com.aegis.core.ai.AiDtos;
import com.aegis.core.audit.DecisionRecord;
import com.aegis.core.audit.DecisionRecord.Action;
import com.aegis.core.audit.DecisionRecord.Subject;
import com.aegis.core.audit.DecisionTrail;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.InventoryStore.ServiceComponentLink;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.sbom.SbomGenerationProperties;
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.matching.MatchingDtos.EvidenceChain;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.UpgradeTargets;
import com.aegis.core.matching.UpgradeTargets.UpgradeTarget;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.common.SensitiveText;
import com.aegis.core.platform.config.PipelineSpan;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.scoring.ScoringService;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignalStore;
import io.micrometer.core.annotation.Timed;
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
 * direct dependency it matches, in every manifest that declares it, and every one another package pulls in,
 * overridden in each project that resolves it, moved to the safe version (deterministic,
 * {@link UpgradeTargets}), with the exact PR title and body and an AI-drafted rationale when the ai-service can
 * supply one. Nothing is written outside Aegis. <b>Approve</b> is the trust boundary: a second person's
 * decision — never the drafter's own — and the only thing that opens a pull request, always a draft, from an
 * {@code aegis/} branch, never merged by Aegis. <b>Dismiss</b> declines with a reason; the drafter may
 * withdraw their own draft this way, since that opens nothing.
 *
 * <p>The repository is always the service's own link, never a caller's input. At approval the manifest
 * is re-read and the edit re-applied, and the write is pinned to the revision just read, so a manifest
 * that changed in between is refused rather than overwritten.
 *
 * <p>Every step — drafted, approved, opened or failed, dismissed — is recorded in the {@link DecisionTrail} with the
 * proposal as it then stood, in the same transaction as the step (E10 story 1).
 */
@Service
public class UpgradeProposalService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeProposalService.class);

    /** An approval interrupted mid-way (a crash while opening) may be retried after this long. */
    static final Duration STALE_OPENING = Duration.ofMinutes(10);
    private static final int MAX_FAILURE_MESSAGE = 1_000;

    private final InventoryService inventory;
    private final InventoryStore inventoryStore;
    private final ScoringService scoring;
    private final UpgradeTargets targets;
    private final ChangeSignalStore signals;
    private final AiDrafter drafter;
    private final PrProviders providers;
    private final UpgradeProposalStore store;
    private final SbomGenerationProperties repositoryHosts;
    private final PullRequestHosts pullRequestHosts;
    private final DecisionTrail trail;

    public UpgradeProposalService(
            InventoryService inventory,
            InventoryStore inventoryStore,
            ScoringService scoring,
            UpgradeTargets targets,
            ChangeSignalStore signals,
            AiDrafter drafter,
            PrProviders providers,
            UpgradeProposalStore store,
            SbomGenerationProperties repositoryHosts,
            PullRequestHosts pullRequestHosts,
            DecisionTrail trail) {
        this.inventory = inventory;
        this.inventoryStore = inventoryStore;
        this.scoring = scoring;
        this.targets = targets;
        this.signals = signals;
        this.drafter = drafter;
        this.providers = providers;
        this.store = store;
        this.repositoryHosts = repositoryHosts;
        this.pullRequestHosts = pullRequestHosts;
        this.trail = trail;
    }

    /**
     * What the upgrade pull request of a response to this change in this service would edit. A dependency the change
     * matches that the service declares is bumped in every manifest the SBOM import found it declared in (the
     * ecosystem's manifest at the repository root when it recorded none, and none at all when it recorded only files
     * Aegis doesn't edit). One another package pulls in — or one a lockfile resolves that nothing declares — is
     * overridden instead, in every project that resolves it ({@link Overrides}): every copy of it in that project's
     * tree moves. Each moves to the lowest version that clears every advisory with a fix for its package
     * ({@link UpgradeTargets}); overrides of one package in one file take the highest of their targets. A dependency
     * another current pull request already upgrades there is left to that one. What can't be automated — an ecosystem
     * or a manifest Aegis doesn't edit, no lockfile recorded to override in, no fixed version, no linked repository, a
     * repository host no pull-request provider serves — is listed with why. Deterministic, and writes nothing.
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

        // Each component this change matched for the service, with every way the service reaches it, shortest first.
        Map<UUID, List<EvidenceChain>> byComponent = new LinkedHashMap<>();
        for (EvidenceChain chain : scored.evidence()) {
            if (chain.componentPath() == null || chain.componentPath().isEmpty()) {
                continue;
            }
            byComponent.computeIfAbsent(chain.componentPath().getFirst().id(), id -> new ArrayList<>()).add(chain);
        }
        byComponent.values().forEach(chains -> chains.sort(Comparator.comparingInt(chain -> chain.componentPath().size())));
        Map<UUID, ServiceComponentLink> links = new HashMap<>();
        inventoryStore.findServiceComponentLinksForServices(List.of(serviceId))
                .forEach(link -> links.put(link.componentId(), link));
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
        List<String> evidence = new ArrayList<>(DraftEvidence.service(scored));
        // The same source the response's ticket records, so the two never disagree on it.
        evidence.addAll(DraftEvidence.source(impact.change(), signals.findById(changeSignalId).orElse(null)));
        MatchConfidence confidence = MatchConfidence.CONFIRMED;
        for (List<EvidenceChain> chains : byComponent.values()) {
            EvidenceChain shortest = chains.getFirst();
            ComponentRef component = shortest.componentPath().getFirst();
            String described = component.name() + " " + component.version();
            if (ManifestEditor.forEcosystem(component.ecosystem()).isEmpty()) {
                notAutomated.add("Aegis drafts upgrades for Maven, npm and PyPI manifests; upgrade " + component.name()
                        + " (" + component.ecosystem() + ") by hand");
                continue;
            }
            Placements placements = shortest.componentPath().size() == 1 && declared(links.get(component.id()))
                    ? declaredIn(component, links.get(component.id()))
                    : overriddenIn(component, chains, links);
            if (placements.files().isEmpty()) {
                notAutomated.add(placements.unavailable());
                continue;
            }
            UpgradeTarget target;
            try {
                target = targets.resolve(changeSignalId, component);
            } catch (ApiExceptions.Conflict unresolved) {
                notAutomated.add(described + ": " + unresolved.getMessage());
                continue;
            }
            vulnerabilityId = target.vulnerabilityId();
            boolean added = false;
            for (Map.Entry<String, Pinning> placement : placements.files().entrySet()) {
                String manifest = placement.getKey();
                Edit edit = new Edit(component.id(), component.ecosystem(), component.name(), component.version(),
                        target.toVersion(), manifest, placement.getValue().override());
                if (claimed.contains(claim(component.id(), manifest))) {
                    if (!edit.follows()) {
                        notAutomated.add(component.name() + " in " + manifest
                                + " is already being upgraded by another upgrade pull request");
                    }
                    continue;
                }
                if (edit.follows() && !added) {
                    continue; // follows a bump, and there is none
                }
                edits.add(edit);
                if (!edit.follows()) {
                    // What follows a bump leaves no step of its own: the bump's lockfile refresh covers it.
                    ManifestEditor editor = ManifestEditor.forEdit(edit);
                    editors.putIfAbsent(editor.fileName(), editor);
                    edited.computeIfAbsent(editor.fileName(), file -> new LinkedHashSet<>()).add(manifest);
                    added = true;
                }
            }
            if (added) {
                checked.addAll(target.checked());
                notCleared.addAll(target.notCleared());
                if (placements.elsewhere() != null) {
                    notAutomated.add(placements.elsewhere());
                }
                if (chains.stream().anyMatch(chain -> chain.confidence() != MatchConfidence.CONFIRMED)) {
                    confidence = MatchConfidence.POSSIBLE;
                }
            }
        }
        edits = overridesAtTheirHighestTarget(edits);
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
     * Where a component goes in the pull request: each file, with how it is pinned there (null: its declaration
     * bumped); or, when nowhere, why — and, when some of the files it was found in aren't edited, which.
     */
    private record Placements(Map<String, Pinning> files, String unavailable, String elsewhere) {
    }

    /** How a component is pinned in one file: its override, or none — its declaration bumped ({@link #BUMP}). */
    private record Pinning(VersionOverride override) {
        static final Pinning BUMP = new Pinning(null);
    }

    /**
     * Whether the service declares a component it links: the import recorded a manifest for it, or nothing at all (an
     * uploaded SBOM, a component linked by hand) — not a package a lockfile resolves that no manifest declares.
     */
    private static boolean declared(ServiceComponentLink link) {
        return link == null || !link.manifestPaths().isEmpty() || link.lockfilePaths().isEmpty();
    }

    /**
     * A declared component: bumped in every manifest that declares it which Aegis edits. For an npm package, any
     * override the project already has of it in another file — its workspace root's package.json, its
     * pnpm-workspace.yaml — follows the bump, if there is one ({@link VersionOverride#following}): left behind, it
     * would keep the old version. (One in a bumped package.json moves with the bump itself.)
     */
    private static Placements declaredIn(ComponentRef component, ServiceComponentLink link) {
        ManifestEditor editor = ManifestEditor.forEcosystem(component.ecosystem()).orElseThrow();
        List<String> recorded = link == null ? List.of() : link.manifestPaths();
        List<String> manifests = editor.manifestsFor(recorded);
        String described = component.name() + " " + component.version();
        if (manifests.isEmpty()) {
            return new Placements(Map.of(), described + " is found in " + String.join(", ", recorded)
                    + ", which Aegis doesn't edit; upgrade it by hand", null);
        }
        Map<String, Pinning> files = new LinkedHashMap<>();
        manifests.forEach(manifest -> files.put(manifest, Pinning.BUMP));
        if (link != null && !link.lockfilePaths().isEmpty()) {
            Overrides.find(component.ecosystem(), recorded, link.lockfilePaths()).targets().stream()
                    .filter(target -> target.packageManager() != null)
                    .forEach(target -> files.putIfAbsent(target.manifestPath(),
                            new Pinning(VersionOverride.following(target.packageManager()))));
        }
        List<String> elsewhere = recorded.stream().map(String::strip)
                .filter(path -> !manifests.contains(path)).distinct().toList();
        return new Placements(files, null, elsewhere.isEmpty() ? null : described + " is also found in "
                + String.join(", ", elsewhere) + ", which Aegis doesn't edit; upgrade it there by hand");
    }

    /**
     * A component another package pulls in, or one a lockfile resolves that nothing declares: overridden in every
     * project that resolves it, reached through each of the service's direct dependencies that leads to it.
     */
    private static Placements overriddenIn(ComponentRef component, List<EvidenceChain> chains,
            Map<UUID, ServiceComponentLink> links) {
        Map<String, Set<String>> viaByFile = new LinkedHashMap<>();
        Map<String, String> managerByFile = new LinkedHashMap<>();
        // What pulls it in where it can't be overridden, and why: the first such route, said once.
        String blockedVia = null;
        String unavailable = null;
        for (EvidenceChain chain : chains) {
            ComponentRef linked = chain.componentPath().getLast();
            String via = chain.componentPath().size() > 1 ? linked.name() : null;
            ServiceComponentLink link = links.get(linked.id());
            Overrides.Found found = Overrides.find(component.ecosystem(),
                    link == null ? List.of() : link.manifestPaths(), link == null ? List.of() : link.lockfilePaths());
            if (found.targets().isEmpty() && unavailable == null) {
                blockedVia = via;
                unavailable = found.unavailable();
            }
            for (Overrides.Target target : found.targets()) {
                Set<String> vias = viaByFile.computeIfAbsent(target.manifestPath(), file -> new LinkedHashSet<>());
                if (via != null) {
                    vias.add(via);
                }
                managerByFile.putIfAbsent(target.manifestPath(), target.packageManager());
            }
        }
        String blocked = unavailable == null ? null
                : Overrides.notOverridden(component.name(), component.version(), blockedVia, unavailable, null);
        if (viaByFile.isEmpty()) {
            return new Placements(Map.of(), blocked, null);
        }
        Map<String, Pinning> files = new LinkedHashMap<>();
        viaByFile.forEach((file, vias) -> files.put(file, new Pinning(new VersionOverride(
                vias.isEmpty() ? null : String.join(", ", vias), managerByFile.get(file)))));
        // Overridden where it can be; another route to it that can't is left to a person, by name.
        return new Placements(files, null, blocked == null ? null : "Also: " + blocked);
    }

    /**
     * One override per package and file: when several versions of a package are overridden in one file (each copy in
     * the tree it pulls in), they all take the highest of their targets, the version the file ends up asking for.
     */
    private static List<Edit> overridesAtTheirHighestTarget(List<Edit> edits) {
        Map<String, String> highest = new HashMap<>();
        for (Edit edit : edits) {
            if (edit.override() != null) {
                highest.merge(edit.manifestPath() + " " + edit.packageName(), edit.toVersion(),
                        (kept, other) -> UpgradeTargets.higher(edit.ecosystem(), kept, other));
            }
        }
        List<Edit> merged = new ArrayList<>();
        for (Edit edit : edits) {
            merged.add(edit.override() == null ? edit : edit.to(highest.get(edit.manifestPath() + " " + edit.packageName())));
        }
        return merged;
    }

    /**
     * Draft the pull request a plan describes, as part of the response (risk ticket) {@code riskTicketId}: the
     * exact title and body, with an AI-drafted rationale when the ai-service can supply one. Writes nothing
     * outside Aegis. Empty when the plan has nothing Aegis can automate.
     */
    @Timed(value = "aegis.draft.duration", histogram = true,
            extraTags = {"kind", "upgrade"})
    @PipelineSpan("aegis.draft.upgrade")
    public Optional<UpgradeProposal> draft(UpgradePlan plan, UUID riskTicketId, UUID drafter) {
        if (!plan.automatable()) {
            return Optional.empty();
        }
        log.info("pipeline stage=draft.upgrade changeSignalId={} serviceId={} riskTicketId={}",
                plan.changeSignalId(), plan.serviceId(), riskTicketId);
        UpgradePlan.Facts planned = plan.facts();
        UUID id = UUID.randomUUID();
        Facts facts = new Facts(id, plan.vulnerabilityId(), planned.checked(), planned.notCleared(),
                planned.serviceName(), planned.criticality(), planned.exposure(), planned.score(), planned.tier(),
                plan.edits(), planned.confidence().name(), planned.evidence(), planned.followUps());
        String body = UpgradePullRequestText.body(facts, rationale(id, facts));
        Edit first = Edit.shown(plan.edits()).getFirst();
        String serviceName = SensitiveText.redact(planned.serviceName());
        return Optional.of(trail.recorded(() -> store.create(new UpgradeProposal(
                id, plan.serviceId(), plan.changeSignalId(), plan.vulnerabilityId(), riskTicketId, plan.edits(),
                planned.repositoryUrl(), planned.baseBranch(), headBranch(first.packageName(), first.toVersion(), id),
                planned.confidence(), planned.score(),
                planned.tier() == null ? null : ImpactTier.valueOf(planned.tier()),
                UpgradePullRequestText.title(Edit.shown(plan.edits()), plan.vulnerabilityId()), body,
                UpgradeProposalStatus.PROPOSED, drafter, null, null, null, null, null, null, null, 0)),
                saved -> record(saved, serviceName, Action.DRAFTED, drafter, null)));
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
     * request); a failure to open it is recorded on the proposal, which can then be approved again or dismissed, and
     * is rethrown to the approver. Once the pull request is open it is never recorded as failing to open: if Aegis
     * can't record it, the proposal stays opening and the approver is told where the pull request is.
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
        String serviceName = SensitiveText.redact(service.getName());
        UpgradeProposal opening = trail.recorded(() -> store.update(proposal.opening(approver, now, title)),
                saved -> record(saved, serviceName, Action.APPROVED, approver, null));
        PullRequestRef pullRequest;
        try {
            pullRequest = openDraftPullRequest(opening, issueKey);
        } catch (RuntimeException failure) {
            try {
                trail.recorded(() -> store.update(opening.failed(failureMessage(failure))),
                        saved -> record(saved, serviceName, Action.OPENING_FAILED, approver, saved.failureMessage()));
            } catch (RuntimeException recording) {
                failure.addSuppressed(recording);
            }
            throw failure;
        }
        try {
            return trail.recorded(() -> store.update(opening.opened(pullRequest)),
                    saved -> record(saved, serviceName, Action.OPENED, approver, null));
        } catch (RuntimeException recording) {
            log.error("upgrade proposal {}: opened as {} but not recorded ({}); it stays opening", opening.id(),
                    pullRequest.url(), recording.getClass().getSimpleName());
            ApiExceptions.Conflict unrecorded = new ApiExceptions.Conflict("The draft pull request is open at "
                    + pullRequest.url() + ", but Aegis couldn't record it: the proposal stays opening. Check the pull "
                    + "request before approving it again");
            unrecorded.initCause(recording);
            throw unrecorded;
        }
    }

    public UpgradeProposal dismiss(UUID proposalId, UUID by, String reason) {
        UpgradeProposal proposal = get(proposalId);
        if (proposal.status() != UpgradeProposalStatus.PROPOSED && proposal.status() != UpgradeProposalStatus.FAILED) {
            throw new ApiExceptions.Conflict("An upgrade proposal that is "
                    + proposal.status().name().toLowerCase(Locale.ROOT) + " cannot be dismissed");
        }
        String serviceName = serviceNames(List.of(proposal.serviceId())).get(proposal.serviceId());
        return trail.recorded(() -> store.update(proposal.dismissed(by, Instant.now(), reason.trim())),
                saved -> record(saved, serviceName, Action.DISMISSED, by, saved.dismissReason()));
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
        inventoryStore.findBusinessServicesByIds(serviceIds)
                .forEach((id, service) -> names.put(id, SensitiveText.redact(service.getName())));
        return names;
    }

    /**
     * A step on this proposal for the decision trail, with the proposal as it now stands and its score and tier as
     * drafted. It belongs to its response (the risk ticket), or stands as its own when it was drafted before
     * responses.
     */
    private static DecisionRecord.Entry record(UpgradeProposal proposal, String serviceName, Action action, UUID actor,
            String reason) {
        return new DecisionRecord.Entry(Subject.UPGRADE_PULL_REQUEST, proposal.id(),
                proposal.riskTicketId() == null ? proposal.id() : proposal.riskTicketId(), proposal.serviceId(),
                serviceName, List.of(proposal.serviceId()), proposal.changeSignalId(), proposal.vulnerabilityId(),
                action, actor, reason, proposal.score(), proposal.tier(), proposal);
    }

    /**
     * Re-read every manifest the proposal edits, apply its edits to each (several in one file in turn), and open
     * the draft pull request with each write pinned to the revision just read. A file an override creates is written
     * as new.
     */
    private PullRequestRef openDraftPullRequest(UpgradeProposal proposal, String issueKey) {
        RepositoryUrl url = RepositoryUrl.parse(proposal.repositoryUrl(), repositoryHosts.allowedHosts());
        RepositoryRef repository = new RepositoryRef(url.host(), url.path());
        PrProvider provider = providers.forRepository(repository);
        Map<String, List<Edit>> byManifest = new LinkedHashMap<>();
        proposal.edits().forEach(edit -> byManifest.computeIfAbsent(edit.manifestPath(), path -> new ArrayList<>())
                .add(edit));
        // What only follows a bump goes last in its file, after whatever the file's own edits set.
        byManifest.values().forEach(edits -> edits.sort(Comparator.comparing(Edit::follows)));
        List<FileChange> changes = new ArrayList<>();
        for (Map.Entry<String, List<Edit>> manifest : byManifest.entrySet()) {
            boolean onlyFollows = manifest.getValue().stream().allMatch(Edit::follows);
            RepositoryFile file;
            try {
                file = provider.readFile(repository, proposal.baseBranch(), manifest.getKey());
            } catch (ApiExceptions.NotFound missing) {
                if (onlyFollows) {
                    continue; // no file, so no override to raise
                }
                // An override can be a file's first setting (pnpm-workspace.yaml): the pull request creates it.
                if (!ManifestEditor.forEdit(manifest.getValue().getFirst()).createsMissing()) {
                    throw missing;
                }
                file = new RepositoryFile(manifest.getKey(), "", null);
            }
            String content = file.content();
            boolean edited = false;
            Set<String> moved = new HashSet<>();
            for (Edit edit : manifest.getValue()) {
                ManifestEditor editor = ManifestEditor.forEdit(edit);
                String kind = edit.override() == null ? "bump" : edit.follows() ? "follow" : "override";
                if (!moved.add(edit.packageName() + " " + edit.toVersion() + " " + kind)) {
                    continue; // two versions of one package in one file: the first edit moved every declaration
                }
                try {
                    content = editor.apply(content, edit);
                    edited = true;
                } catch (ApiExceptions.Conflict refused) {
                    // Two packages sharing one version property: the first edit already moved it.
                    if (!edited || edit.override() != null || !alreadyAt(editor, content, edit)) {
                        throw refused;
                    }
                }
            }
            if (onlyFollows && content.equals(file.content())) {
                continue; // the file overrides none of it: nothing to raise
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

    private Rationale rationale(UUID proposalId, Facts facts) {
        try {
            AiDtos.Draft draft = drafter.draft(new AiDtos.DraftRequest(proposalId.toString(), "upgrade", context(facts)));
            return Rationale.drafted(draft.content(), draft.citations());
        } catch (RuntimeException unavailable) {
            // The deterministic facts stand on their own: an AI outage must never block a fix.
            log.warn("upgrade proposal {}: no AI rationale ({})", proposalId, unavailable.getClass().getSimpleName());
            return Rationale.unavailable("the AI service could not draft one");
        }
    }

    /** The ai-service's grounding: the advisory's own text and the deterministic facts, nothing else. */
    private static String context(Facts facts) {
        String upgrades = Edit.shown(facts.edits()).stream()
                .map(edit -> edit.packageName() + " " + edit.fromVersion() + " to " + edit.toVersion() + " in "
                        + edit.manifestPath() + (edit.override() == null ? "" : " (an override: "
                        + (edit.override().via() == null ? "no manifest declares it"
                                : "it comes in through " + edit.override().via()) + ")"))
                .collect(Collectors.joining("; "));
        return """
                Vulnerability %s
                Proposed upgrades: %s. Each target is the lowest version outside every published affected range \
                with a fix (checked against %s).
                Service: %s (%s criticality, %s), impact score %s (%s).
                Evidence: %s
                """.formatted(facts.vulnerabilityId(), upgrades, String.join(", ", facts.checked()),
                facts.serviceName(), facts.criticality(), facts.exposure(), facts.score(), facts.tier(),
                String.join("\n", facts.evidence()));
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
