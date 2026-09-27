package com.aegis.core.actions.upgrade.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.aegis.core.actions.upgrade.UpgradeProposal;
import com.aegis.core.actions.upgrade.UpgradeProposalStatus;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Row for {@code upgrade_proposals} (V18, V27). The plan is immutable once written; only the decision moves,
 * and the title once, when a response's pull request is opened under its risk ticket's key.
 */
@Entity
@Table(name = "upgrade_proposals")
class UpgradeProposalEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "change_signal_id", nullable = false, updatable = false)
    private UUID changeSignalId;

    @Column(name = "vulnerability_id", nullable = false, updatable = false, length = 64)
    private String vulnerabilityId;

    @Column(name = "risk_ticket_id", updatable = false)
    private UUID riskTicketId;

    /** The edits as JSON (V27); the store maps them. */
    @Column(name = "edits", nullable = false, updatable = false, columnDefinition = "text")
    private String edits;

    @Column(name = "repository_url", nullable = false, updatable = false, length = 400)
    private String repositoryUrl;

    @Column(name = "base_branch", updatable = false, length = 200)
    private String baseBranch;

    @Column(name = "head_branch", nullable = false, updatable = false, length = 200)
    private String headBranch;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_confidence", nullable = false, updatable = false, length = 16)
    private MatchConfidence matchConfidence;

    /** Updatable: opening a response's pull request puts its risk ticket's key in front. */
    @Column(name = "title", nullable = false, length = 256)
    private String title;

    @Column(name = "body", nullable = false, updatable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UpgradeProposalStatus status;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "dismiss_reason", length = 1000)
    private String dismissReason;

    @Column(name = "pull_request_url", length = 400)
    private String pullRequestUrl;

    @Column(name = "pull_request_number", length = 32)
    private String pullRequestNumber;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected UpgradeProposalEntity() {
        // Required by JPA.
    }

    static UpgradeProposalEntity create(UUID orgId, UpgradeProposal proposal, String editsJson) {
        UpgradeProposalEntity entity = new UpgradeProposalEntity();
        entity.id = proposal.id();
        entity.orgId = orgId;
        entity.serviceId = proposal.serviceId();
        entity.changeSignalId = proposal.changeSignalId();
        entity.vulnerabilityId = proposal.vulnerabilityId();
        entity.riskTicketId = proposal.riskTicketId();
        entity.edits = editsJson;
        entity.repositoryUrl = proposal.repositoryUrl();
        entity.baseBranch = proposal.baseBranch();
        entity.headBranch = proposal.headBranch();
        entity.matchConfidence = proposal.matchConfidence();
        entity.title = proposal.title();
        entity.body = proposal.body();
        entity.createdBy = proposal.createdBy();
        entity.applyDecision(proposal);
        return entity;
    }

    /** Copy the parts of a proposal that a decision changes. */
    void applyDecision(UpgradeProposal proposal) {
        this.title = proposal.title();
        this.status = proposal.status();
        this.decidedBy = proposal.decidedBy();
        this.decidedAt = proposal.decidedAt();
        this.dismissReason = proposal.dismissReason();
        this.pullRequestUrl = proposal.pullRequestUrl();
        this.pullRequestNumber = proposal.pullRequestNumber();
        this.failureMessage = proposal.failureMessage();
    }

    UpgradeProposal toDomain(List<UpgradeProposal.Edit> parsedEdits) {
        return new UpgradeProposal(id, serviceId, changeSignalId, vulnerabilityId, riskTicketId, parsedEdits,
                repositoryUrl, baseBranch, headBranch, matchConfidence, title, body, status, createdBy, createdAt,
                decidedBy, decidedAt, dismissReason, pullRequestUrl, pullRequestNumber, failureMessage, version);
    }

    String edits() {
        return edits;
    }

    long getVersion() {
        return version;
    }
}
