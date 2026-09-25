package com.aegis.core.actions.upgrade;

import java.time.Instant;
import java.util.UUID;

import com.aegis.core.actions.pr.PullRequestRef;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;

/**
 * A reviewed plan to fix one vulnerability in one service by upgrading one component (E8 story 4): which
 * package moves from which version to which in which manifest, and the exact pull-request title and body.
 * An approver sees all of it before approving, and nothing reaches the repository until they do.
 *
 * <p>The repository and base branch are the service's link when the proposal was drafted; approval
 * refuses if the link has changed since. {@code version} is the optimistic lock that makes two people
 * deciding at once safe.
 */
public record UpgradeProposal(
        UUID id,
        UUID serviceId,
        UUID changeSignalId,
        String vulnerabilityId,
        UUID componentId,
        String ecosystem,
        String packageName,
        String fromVersion,
        String toVersion,
        String manifestPath,
        String repositoryUrl,
        String baseBranch,
        String headBranch,
        MatchConfidence matchConfidence,
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

    /** Approved by {@code approver}; the draft pull request is about to be opened. */
    UpgradeProposal opening(UUID approver, Instant at) {
        return transition(UpgradeProposalStatus.OPENING, approver, at, null, null, null, null);
    }

    UpgradeProposal opened(PullRequestRef pullRequest) {
        return transition(UpgradeProposalStatus.OPENED, decidedBy, decidedAt, null,
                pullRequest.url(), pullRequest.number(), null);
    }

    UpgradeProposal failed(String reason) {
        return transition(UpgradeProposalStatus.FAILED, decidedBy, decidedAt, null, null, null, reason);
    }

    UpgradeProposal dismissed(UUID by, Instant at, String reason) {
        return transition(UpgradeProposalStatus.DISMISSED, by, at, reason, null, null, failureMessage);
    }

    private UpgradeProposal transition(
            UpgradeProposalStatus status, UUID decidedBy, Instant decidedAt, String dismissReason,
            String pullRequestUrl, String pullRequestNumber, String failureMessage) {
        return new UpgradeProposal(id, serviceId, changeSignalId, vulnerabilityId, componentId, ecosystem,
                packageName, fromVersion, toVersion, manifestPath, repositoryUrl, baseBranch, headBranch,
                matchConfidence, title, body, status, createdBy, createdAt, decidedBy, decidedAt, dismissReason,
                pullRequestUrl, pullRequestNumber, failureMessage, version);
    }
}
