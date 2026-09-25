package com.aegis.core.actions.upgrade;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.aegis.core.actions.pr.FileChange;
import com.aegis.core.actions.pr.PrProvider;
import com.aegis.core.actions.pr.PrProviders;
import com.aegis.core.actions.pr.PullRequestDraft;
import com.aegis.core.actions.pr.PullRequestRef;
import com.aegis.core.actions.pr.RepositoryFile;
import com.aegis.core.actions.pr.RepositoryRef;
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
 *
 * <p><b>Propose</b> turns "this CVE reaches this service through this component" into a reviewed plan:
 * the safe version (deterministic, {@link UpgradeTargets}), the manifest to edit, and the exact PR title
 * and body, with an AI-drafted rationale when the ai-service can supply one. It writes nothing outside
 * Aegis. <b>Approve</b> is the trust boundary: a second person's decision — never the drafter's own — and
 * the only thing that opens a pull request, always a draft, from an {@code aegis/} branch, never merged by
 * Aegis. <b>Dismiss</b> declines with a reason; the drafter may withdraw their own draft this way, since
 * that opens nothing and the vulnerability stays recommended.
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

    public UpgradeProposalService(
            InventoryService inventory,
            InventoryStore inventoryStore,
            ScoringService scoring,
            UpgradeTargets targets,
            AdvisoryStore advisories,
            AiDrafter drafter,
            PrProviders providers,
            UpgradeProposalStore store,
            SbomGenerationProperties repositoryHosts) {
        this.inventory = inventory;
        this.inventoryStore = inventoryStore;
        this.scoring = scoring;
        this.targets = targets;
        this.advisories = advisories;
        this.drafter = drafter;
        this.providers = providers;
        this.store = store;
        this.repositoryHosts = repositoryHosts;
    }

    public UpgradeProposal propose(
            UUID changeSignalId, UUID serviceId, UUID componentId, String manifestPath, UUID proposer) {
        BusinessService service = inventory.getBusinessService(serviceId);
        if (service.getRepositoryUrl() == null) {
            throw new ApiExceptions.Conflict("Link the service's repository before drafting an upgrade pull request");
        }
        ChangeImpactResponse impact = scoring.scoreChange(changeSignalId);
        if (impact.change().signalType() != ChangeSignal.SignalType.VULNERABILITY) {
            throw new ApiExceptions.BadRequest("Only a vulnerability can be remediated by an upgrade");
        }
        ScoredService scored = impact.services().stream()
                .filter(candidate -> candidate.service().id().equals(serviceId))
                .findFirst()
                .orElseThrow(() -> new ApiExceptions.Conflict("This change does not reach the service"));
        // The component must be one this change matched for this service; a direct use wins over a transitive one.
        EvidenceChain evidence = scored.evidence().stream()
                .filter(chain -> !chain.componentPath().isEmpty()
                        && chain.componentPath().getFirst().id().equals(componentId))
                .min(Comparator.comparingInt(chain -> chain.componentPath().size()))
                .orElseThrow(() -> new ApiExceptions.Conflict(
                        "The component is not one this change matches for the service"));
        ComponentRef component = evidence.componentPath().getFirst();
        if (evidence.componentPath().size() > 1) {
            String direct = evidence.componentPath().getLast().name();
            throw new ApiExceptions.Conflict(component.name() + " reaches the service through " + direct
                    + "; transitive upgrades are not automated yet, so upgrade " + direct + " or pin "
                    + component.name() + " by hand");
        }
        ManifestEditor editor = ManifestEditor.forEcosystem(component.ecosystem())
                .orElseThrow(() -> new ApiExceptions.Conflict(
                        "Automated upgrades support Maven, npm and PyPI manifests, not " + component.ecosystem()));
        String manifest = manifestPath(manifestPath, recordedManifest(serviceId, componentId), editor);
        store.findLive(serviceId, changeSignalId, componentId).ifPresent(live -> {
            throw new ApiExceptions.Conflict("This upgrade is already " + live.status().name().toLowerCase(Locale.ROOT)
                    + " as proposal " + live.id());
        });
        UpgradeTarget target = targets.resolve(changeSignalId, component);

        UUID id = UUID.randomUUID();
        Facts facts = new Facts(id, target.vulnerabilityId(), target.checked(), target.notCleared(), service.getName(),
                service.getCriticality().name(), service.getExposure().name(), scored.score(), scored.tier().name(),
                component.name(), component.version(), target.toVersion(), manifest, evidence.confidence().name(),
                evidence.explanation(), editor.followUps());
        String body = UpgradePullRequestText.body(facts, rationale(id, impact.change(), facts));
        return store.create(new UpgradeProposal(
                id, serviceId, changeSignalId, target.vulnerabilityId(), componentId, component.ecosystem(),
                component.name(), component.version(), target.toVersion(), manifest, service.getRepositoryUrl(),
                service.getRepositoryBranch(), headBranch(component.name(), target.toVersion(), id),
                evidence.confidence(),
                UpgradePullRequestText.title(component.name(), component.version(), target.toVersion(),
                        target.vulnerabilityId()),
                body, UpgradeProposalStatus.PROPOSED, proposer, null, null, null, null, null, null, null, 0));
    }

    /**
     * A person's approval: open the draft pull request. The approver is never the drafter — retrying a
     * failed opening is approving too. The proposal is claimed first (so a second, concurrent approval gets
     * a conflict, not a second pull request); a failure is recorded on the proposal, which can then be
     * approved again or dismissed, and is rethrown to the approver.
     */
    public UpgradeProposal approve(UUID proposalId, UUID approver) {
        UpgradeProposal proposal = get(proposalId);
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
        UpgradeProposal opening = store.update(proposal.opening(approver, now));
        try {
            return store.update(opening.opened(openDraftPullRequest(opening)));
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

    public Page<UpgradeProposal> list(
            UUID serviceId, UUID changeSignalId, Collection<UpgradeProposalStatus> statuses, Pageable pageable) {
        return store.findPage(serviceId, changeSignalId, statuses, pageable);
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

    private PullRequestRef openDraftPullRequest(UpgradeProposal proposal) {
        RepositoryUrl url = RepositoryUrl.parse(proposal.repositoryUrl(), repositoryHosts.allowedHosts());
        RepositoryRef repository = new RepositoryRef(url.host(), url.path());
        PrProvider provider = providers.forRepository(repository);
        ManifestEditor editor = ManifestEditor.forEcosystem(proposal.ecosystem())
                .orElseThrow(() -> new ApiExceptions.Conflict("No manifest editor for " + proposal.ecosystem()));
        RepositoryFile manifest = provider.readFile(repository, proposal.baseBranch(), proposal.manifestPath());
        String upgraded = editor.upgrade(
                manifest.content(), proposal.packageName(), proposal.fromVersion(), proposal.toVersion());
        return provider.openDraftPullRequest(new PullRequestDraft(
                repository, proposal.baseBranch(), proposal.headBranch(), proposal.title(), proposal.body(),
                proposal.title(), List.of(new FileChange(proposal.manifestPath(), upgraded, manifest.revision()))));
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
        return """
                Vulnerability %s: %s
                Affected package: %s, installed version %s.
                Proposed upgrade: %s, the lowest version outside every published affected range with a fix \
                (checked against %s).
                Service: %s (%s criticality, %s), impact score %s (%s).
                Evidence: %s
                """.formatted(facts.vulnerabilityId(), description, facts.packageName(), facts.fromVersion(),
                facts.toVersion(), String.join(", ", facts.checked()), facts.serviceName(), facts.criticality(),
                facts.exposure(), facts.score(), facts.tier(), facts.evidence());
    }

    /** The manifest the SBOM import recorded as declaring this dependency (client/package.json), if any. */
    private String recordedManifest(UUID serviceId, UUID componentId) {
        return inventoryStore.findServiceComponentLinksForServices(List.of(serviceId)).stream()
                .filter(link -> link.componentId().equals(componentId) && link.manifestPath() != null)
                .map(InventoryStore.ServiceComponentLink::manifestPath)
                .findFirst().orElse(null);
    }

    /**
     * The caller's manifest path, else the one the import recorded, else the ecosystem's at the root. A
     * caller's unusable path is refused; a recorded one that doesn't fit this editor is simply not used.
     */
    private static String manifestPath(String requested, String recorded, ManifestEditor editor) {
        if (requested == null || requested.isBlank()) {
            try {
                return recorded == null ? editor.fileName() : checkedManifest(recorded, editor);
            } catch (ApiExceptions.BadRequest unusable) {
                return editor.fileName();
            }
        }
        return checkedManifest(requested, editor);
    }

    private static String checkedManifest(String requested, ManifestEditor editor) {
        String path = requested.trim();
        FileChange.requireSafePath(path);
        if (!path.equals(editor.fileName()) && !path.endsWith("/" + editor.fileName())) {
            throw new ApiExceptions.BadRequest("This component's manifest must be a " + editor.fileName());
        }
        return path;
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
