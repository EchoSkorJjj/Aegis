package com.aegis.core.actions.upgrade;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.pr.PullRequestRef;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.platform.common.SensitiveText;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;

/**
 * A reviewed plan to fix one vulnerability in one service with one pull request (E8 story 4): every edit it
 * makes — which package moves from which version to which, in which manifest of the service's repository —
 * and the exact pull-request title and body. An approver sees all of it before approving, and nothing reaches
 * the repository until they do.
 *
 * <p>{@code riskTicketId} is the response it belongs to (E8 story 5): the risk ticket for the same
 * vulnerability and service, decided together. It is null only for a proposal drafted on its own before
 * responses existed. The repository and base branch are the service's link when the proposal was drafted;
 * approval refuses if the link has changed since. {@code score} and {@code tier} are the service's when the proposal
 * was drafted, as its ticket keeps them (null for a proposal drafted before V36). {@code version} is the optimistic
 * lock that makes two people deciding at once safe.
 */
public record UpgradeProposal(
        UUID id,
        UUID serviceId,
        UUID changeSignalId,
        String vulnerabilityId,
        UUID riskTicketId,
        List<Edit> edits,
        String repositoryUrl,
        String baseBranch,
        String headBranch,
        MatchConfidence matchConfidence,
        Double score,
        ImpactTier tier,
        String title,
        String body,
        UpgradeProposalStatus status,
        UUID createdBy,
        Instant createdAt,
        UUID decidedBy,
        Instant decidedAt,
        String dismissReason,
        String pullRequestUrl,
        String pullRequestNumber,
        String failureMessage,
        long version) {

    public UpgradeProposal {
        edits = List.copyOf(edits);
    }

    /**
     * One package moved from one version to another in one manifest. {@code override} is null when the manifest
     * declares the package and that declaration is bumped. Otherwise no manifest declares it — another package pulls it
     * in — and the manifest overrides its version for the whole project instead ({@link ManifestEditor#override}).
     */
    public record Edit(UUID componentId, String ecosystem, String packageName, String fromVersion, String toVersion,
                       String manifestPath, VersionOverride override) {

        /** A declared package's version, bumped in the manifest that declares it. */
        public Edit(UUID componentId, String ecosystem, String packageName, String fromVersion, String toVersion,
                String manifestPath) {
            this(componentId, ecosystem, packageName, fromVersion, toVersion, manifestPath, null);
        }

        Edit to(String version) {
            return new Edit(componentId, ecosystem, packageName, fromVersion, version, manifestPath, override);
        }

        /** Only raises an override the file may already have, following a bump elsewhere in the pull request. */
        public boolean follows() {
            return override != null && override.ifPresent();
        }

        /** The edits a person reads as the pull request's changes: every bump and override, not what only follows. */
        static List<Edit> shown(List<Edit> edits) {
            return edits.stream().filter(edit -> !edit.follows()).toList();
        }
    }

    /**
     * How an edit pins a package no manifest declares. {@code via} names the declared dependencies that pull it in
     * ({@code shadcn}), or is null for a package a lockfile resolves that no declared dependency leads to.
     * {@code packageManager} is whose syntax an npm package's override takes — {@code npm} ({@code overrides} in
     * package.json), {@code pnpm} ({@code overrides} in pnpm-workspace.yaml) or {@code yarn} ({@code resolutions} in
     * package.json) — and null for Maven ({@code dependencyManagement}) and PyPI (a requirement of its own).
     *
     * <p>{@code ifPresent} marks an edit that only follows a bump: the package is declared and its declaration moves
     * elsewhere in the pull request, and an override of it the project already has — in its workspace root or
     * pnpm-workspace.yaml — is raised with it, or the override would keep the old version. Without one the file is
     * left alone ({@link ManifestEditor#raiseOverride}).
     */
    public record VersionOverride(String via, String packageManager, boolean ifPresent) {

        public VersionOverride(String via, String packageManager) {
            this(via, packageManager, false);
        }

        /** An existing override of a declared package, raised with its declaration if the file has one. */
        static VersionOverride following(String packageManager) {
            return new VersionOverride(null, packageManager, true);
        }
    }

    /** Approved by {@code approver}; the draft pull request is about to be opened under {@code title}. */
    UpgradeProposal opening(UUID approver, Instant at, String title) {
        return transition(UpgradeProposalStatus.OPENING, approver, at, null, null, null, null, title);
    }

    UpgradeProposal opened(PullRequestRef pullRequest) {
        return transition(UpgradeProposalStatus.OPENED, decidedBy, decidedAt, null,
                pullRequest.url(), pullRequest.number(), null, title);
    }

    /** An upstream error can quote the request it rejected, so it is redacted as it enters the record. */
    UpgradeProposal failed(String reason) {
        return transition(UpgradeProposalStatus.FAILED, decidedBy, decidedAt, null, null, null,
                SensitiveText.redact(reason), title);
    }

    UpgradeProposal dismissed(UUID by, Instant at, String reason) {
        return transition(UpgradeProposalStatus.DISMISSED, by, at, SensitiveText.redact(reason), null, null,
                failureMessage, title);
    }

    private UpgradeProposal transition(
            UpgradeProposalStatus status, UUID decidedBy, Instant decidedAt, String dismissReason,
            String pullRequestUrl, String pullRequestNumber, String failureMessage, String title) {
        return new UpgradeProposal(id, serviceId, changeSignalId, vulnerabilityId, riskTicketId, edits, repositoryUrl,
                baseBranch, headBranch, matchConfidence, score, tier, title, body, status, createdBy, createdAt,
                decidedBy, decidedAt, dismissReason, pullRequestUrl, pullRequestNumber, failureMessage, version);
    }
}
