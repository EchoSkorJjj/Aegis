package com.aegis.core.actions.ticket.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.aegis.core.actions.ticket.RiskTicket;
import com.aegis.core.actions.ticket.RiskTicketStatus;
import com.aegis.core.actions.tracker.IssueTracker.Priority;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.signals.ChangeSignal.SignalType;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

/**
 * A risk ticket row (V23). What Aegis worked out never changes; while the ticket awaits a decision a reviewer may
 * edit its narrative and override its priority and due date (V33), and the decision fields record the rest.
 * {@code pendingPullRequests} is read from its response's upgrade pull requests (V27): how many still need a
 * person — awaiting a decision, being opened, or failed — so a queue can tell which responses do.
 */
@Entity
@Table(name = "risk_tickets")
class RiskTicketEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "change_signal_id", nullable = false, updatable = false)
    private UUID changeSignalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, updatable = false, length = 16)
    private SignalType signalType;

    @Column(name = "change_title", nullable = false, updatable = false, length = 300)
    private String changeTitle;

    @Column(name = "title", nullable = false, updatable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private Priority priority;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "score", nullable = false, updatable = false)
    private double score;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, updatable = false, length = 16)
    private ImpactTier tier;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "labels", nullable = false, updatable = false, columnDefinition = "text[]")
    private String[] labels;

    @Column(name = "summary", nullable = false, columnDefinition = "text")
    private String summary;

    @Column(name = "risk", nullable = false, columnDefinition = "text")
    private String risk;

    @Column(name = "impact", nullable = false, columnDefinition = "text")
    private String impact;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "actions", nullable = false, columnDefinition = "text[]")
    private String[] actions;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "evidence", nullable = false, updatable = false, columnDefinition = "text[]")
    private String[] evidence;

    @Column(name = "ai_drafted", nullable = false, updatable = false)
    private boolean aiDrafted;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "citations", nullable = false, updatable = false, columnDefinition = "text[]")
    private String[] citations;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RiskTicketStatus status;

    @Formula("(select count(*) from {h-schema}upgrade_proposals p where p.risk_ticket_id = id"
            + " and p.status in ('PROPOSED', 'OPENING', 'FAILED'))")
    private int pendingPullRequests;

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

    @Column(name = "issue_key", length = 64)
    private String issueKey;

    @Column(name = "issue_url", length = 400)
    private String issueUrl;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @Column(name = "drafted_summary", columnDefinition = "text")
    private String draftedSummary;

    @Column(name = "drafted_risk", columnDefinition = "text")
    private String draftedRisk;

    @Column(name = "drafted_impact", columnDefinition = "text")
    private String draftedImpact;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "drafted_actions", columnDefinition = "text[]")
    private String[] draftedActions;

    @Column(name = "edited_by")
    private UUID editedBy;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "assessed_priority", length = 16)
    private Priority assessedPriority;

    @Column(name = "assessed_due_date")
    private LocalDate assessedDueDate;

    @Column(name = "override_reason", length = 1000)
    private String overrideReason;

    @Column(name = "overridden_by")
    private UUID overriddenBy;

    @Column(name = "overridden_at")
    private Instant overriddenAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected RiskTicketEntity() {
    }

    static RiskTicketEntity create(UUID orgId, RiskTicket ticket) {
        RiskTicketEntity entity = new RiskTicketEntity();
        entity.id = ticket.id();
        entity.orgId = orgId;
        entity.serviceId = ticket.serviceId();
        entity.changeSignalId = ticket.changeSignalId();
        entity.signalType = ticket.signalType();
        entity.changeTitle = ticket.changeTitle();
        entity.title = ticket.title();
        entity.priority = ticket.priority();
        entity.dueDate = ticket.dueDate();
        entity.score = ticket.score();
        entity.tier = ticket.tier();
        entity.labels = ticket.labels().toArray(String[]::new);
        entity.summary = ticket.summary();
        entity.risk = ticket.risk();
        entity.impact = ticket.impact();
        entity.actions = ticket.actions().toArray(String[]::new);
        entity.evidence = ticket.evidence().toArray(String[]::new);
        entity.aiDrafted = ticket.aiDrafted();
        entity.citations = ticket.citations().toArray(String[]::new);
        entity.status = ticket.status();
        entity.createdBy = ticket.createdBy();
        return entity;
    }

    /** Everything a reviewer may change: the narrative, priority and due date, and the decision. */
    void apply(RiskTicket ticket) {
        this.priority = ticket.priority();
        this.dueDate = ticket.dueDate();
        this.summary = ticket.summary();
        this.risk = ticket.risk();
        this.impact = ticket.impact();
        this.actions = ticket.actions().toArray(String[]::new);
        RiskTicket.DraftEdit edit = ticket.edit();
        this.draftedSummary = edit == null ? null : edit.draftedSummary();
        this.draftedRisk = edit == null ? null : edit.draftedRisk();
        this.draftedImpact = edit == null ? null : edit.draftedImpact();
        this.draftedActions = edit == null ? null : edit.draftedActions().toArray(String[]::new);
        this.editedBy = edit == null ? null : edit.by();
        this.editedAt = edit == null ? null : edit.at();
        RiskTicket.AssessmentOverride override = ticket.override();
        this.assessedPriority = override == null ? null : override.assessedPriority();
        this.assessedDueDate = override == null ? null : override.assessedDueDate();
        this.overrideReason = override == null ? null : override.reason();
        this.overriddenBy = override == null ? null : override.by();
        this.overriddenAt = override == null ? null : override.at();
        this.status = ticket.status();
        this.decidedBy = ticket.decidedBy();
        this.decidedAt = ticket.decidedAt();
        this.dismissReason = ticket.dismissReason();
        this.issueKey = ticket.issueKey();
        this.issueUrl = ticket.issueUrl();
        this.failureMessage = ticket.failureMessage();
    }

    long getVersion() {
        return version;
    }

    RiskTicket toDomain() {
        return new RiskTicket(id, serviceId, changeSignalId, signalType, changeTitle, title, priority, dueDate, score,
                tier, List.of(labels), summary, risk, impact, List.of(actions), List.of(evidence), aiDrafted,
                List.of(citations), status, createdBy, createdAt, decidedBy, decidedAt, dismissReason, issueKey,
                issueUrl, failureMessage,
                editedAt == null ? null : new RiskTicket.DraftEdit(draftedSummary, draftedRisk, draftedImpact,
                        List.of(draftedActions), editedBy, editedAt),
                overriddenAt == null ? null : new RiskTicket.AssessmentOverride(assessedPriority, assessedDueDate,
                        overrideReason, overriddenBy, overriddenAt),
                version);
    }
}

interface RiskTicketJpaRepository
        extends JpaRepository<RiskTicketEntity, UUID>, JpaSpecificationExecutor<RiskTicketEntity> {

    Optional<RiskTicketEntity> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The row, locked until the transaction ends ({@code select … for update}): a concurrent decision waits, then
     * sees the version this one wrote, so it is a conflict rather than a second outcome.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RiskTicketEntity> findForUpdateByIdAndOrgId(UUID id, UUID orgId);

    Optional<RiskTicketEntity> findFirstByOrgIdAndServiceIdAndChangeSignalIdAndStatusIn(
            UUID orgId, UUID serviceId, UUID changeSignalId, List<RiskTicketStatus> statuses);

    List<RiskTicketEntity> findByOrgIdAndServiceIdInAndStatusIn(
            UUID orgId, Collection<UUID> serviceIds, List<RiskTicketStatus> statuses, Sort sort);
}
