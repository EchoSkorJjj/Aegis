package com.aegis.core.actions.upgrade;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.pr.PullRequestRef;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.platform.common.SensitiveText;

/**
 * A reviewed plan to fix one vulnerability in one service with one pull request (E8 story 4): every edit it
 * makes — which package moves from which version to which, in which manifest of the service's repository —
 * and the exact pull-request title and body. An approver sees all of it before approving, and nothing reaches
 * the repository until they do.
 *
 * <p>{@code riskTicketId} is the response it belongs to (E8 story 5): the risk ticket for the same
 * vulnerability and service, decided together. It is null only for a proposal drafted on its own before
 * responses existed. The repository and base branch are the service's link when the proposal was drafted;
 * approval refuses if the link has changed since. {@code version} is the optimistic lock that makes two
 * people deciding at once safe.
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
        title = SensitiveText.redact(title);
        body = SensitiveText.redact(body);
        dismissReason = SensitiveText.redact(dismissReason);
        failureMessage = SensitiveText.redact(failureMessage);
    }

    /** One package moved from one version to another in one manifest. */
    public record Edit(UUID componentId, String ecosystem, String packageName, String fromVersion, String toVersion,
                       String manifestPath) {
    }

    /** Approved by {@code approver}; the draft pull request is about to be opened under {@code title}. */
    UpgradeProposal opening(UUID approver, Instant at, String title) {
        return transition(UpgradeProposalStatus.OPENING, approver, at, null, null, null, null, title);
    }

    UpgradeProposal opened(PullRequestRef pullRequest) {
        return transition(UpgradeProposalStatus.OPENED, decidedBy, decidedAt, null,
                pullRequest.url(), pullRequest.number(), null, title);
    }

    UpgradeProposal failed(String reason) {
        return transition(UpgradeProposalStatus.FAILED, decidedBy, decidedAt, null, null, null, reason, title);
    }

    UpgradeProposal dismissed(UUID by, Instant at, String reason) {
        return transition(UpgradeProposalStatus.DISMISSED, by, at, reason, null, null, failureMessage, title);
    }

    private UpgradeProposal transition(
            UpgradeProposalStatus status, UUID decidedBy, Instant decidedAt, String dismissReason,
            String pullRequestUrl, String pullRequestNumber, String failureMessage, String title) {
        return new UpgradeProposal(id, serviceId, changeSignalId, vulnerabilityId, riskTicketId, edits, repositoryUrl,
                baseBranch, headBranch, matchConfidence, title, body, status, createdBy, createdAt, decidedBy, decidedAt,
                dismissReason, pullRequestUrl, pullRequestNumber, failureMessage, version);
    }
}
