package com.aegis.core.actions.ticket;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.tracker.IssueTracker.FiledIssue;
import com.aegis.core.actions.tracker.IssueTracker.Priority;
import com.aegis.core.platform.common.SensitiveText;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.signals.ChangeSignal.SignalType;

/**
 * A drafted risk ticket for one change's impact on one service (E8 story 5): what a person reviews before
 * Aegis files it in the organization's tracker. The title, priority, due date, labels and evidence are
 * Aegis's own, worked out from the impact score; the summary, risk, impact and actions are the AI's draft
 * ({@code aiDrafted}), or Aegis's plain fallback when the AI could not draft them.
 *
 * <p>While it awaits a decision a reviewer may {@linkplain #edited edit} the narrative and
 * {@linkplain #overridden override} the priority and due date (E9 story 3). The ticket's own fields are always
 * what will be filed; {@code edit} keeps the narrative as drafted, and {@code override} Aegis's own assessment and
 * why it was replaced. Each is null until it happens.
 *
 * <p>{@code version} is the optimistic lock that makes two people deciding at once safe.
 */
public record RiskTicket(
        UUID id,
        UUID serviceId,
        UUID changeSignalId,
        SignalType signalType,
        String changeTitle,
        String title,
        Priority priority,
        LocalDate dueDate,
        double score,
        ImpactTier tier,
        List<String> labels,
        String summary,
        String risk,
        String impact,
        List<String> actions,
        List<String> evidence,
        boolean aiDrafted,
        List<String> citations,
        RiskTicketStatus status,
        UUID createdBy,
        Instant createdAt,
        UUID decidedBy,
        Instant decidedAt,
        String dismissReason,
        String issueKey,
        String issueUrl,
        String failureMessage,
        DraftEdit edit,
        AssessmentOverride override,
        long version) {

    public RiskTicket {
        labels = List.copyOf(labels);
        actions = List.copyOf(actions);
        evidence = List.copyOf(evidence);
        citations = List.copyOf(citations);
    }

    /** The narrative as drafted, before a reviewer first edited it, and who edited it last, when. */
    public record DraftEdit(String draftedSummary, String draftedRisk, String draftedImpact, List<String> draftedActions,
                            UUID by, Instant at) {

        public DraftEdit {
            draftedActions = List.copyOf(draftedActions);
        }
    }

    /**
     * Aegis's own priority and due date (from the impact tier), which a reviewer replaced, why, and who did, when.
     * The assessed due date is null when the tier sets none.
     */
    public record AssessmentOverride(Priority assessedPriority, LocalDate assessedDueDate, String reason, UUID by,
                                     Instant at) {
    }

    /** The narrative rewritten by {@code by}; the draft as it first was is kept. */
    RiskTicket edited(String summary, String risk, String impact, List<String> actions, UUID by, Instant at) {
        DraftEdit drafted = edit == null
                ? new DraftEdit(this.summary, this.risk, this.impact, this.actions, by, at)
                : new DraftEdit(edit.draftedSummary(), edit.draftedRisk(), edit.draftedImpact(), edit.draftedActions(),
                        by, at);
        return new RiskTicket(id, serviceId, changeSignalId, signalType, changeTitle, title, priority, dueDate, score,
                tier, labels, summary, risk, impact, actions, evidence, aiDrafted, citations, status, createdBy,
                createdAt, decidedBy, decidedAt, dismissReason, issueKey, issueUrl, failureMessage, drafted, override,
                version);
    }

    /** Aegis's priority and due date replaced by {@code by}, with why; Aegis's own assessment is kept. */
    RiskTicket overridden(Priority priority, LocalDate dueDate, String reason, UUID by, Instant at) {
        AssessmentOverride assessed = override == null
                ? new AssessmentOverride(this.priority, this.dueDate, reason, by, at)
                : new AssessmentOverride(override.assessedPriority(), override.assessedDueDate(), reason, by, at);
        return new RiskTicket(id, serviceId, changeSignalId, signalType, changeTitle, title, priority, dueDate, score,
                tier, labels, summary, risk, impact, actions, evidence, aiDrafted, citations, status, createdBy,
                createdAt, decidedBy, decidedAt, dismissReason, issueKey, issueUrl, failureMessage, edit, assessed,
                version);
    }

    /** Approved by {@code approver}; the issue is about to be filed. */
    RiskTicket filing(UUID approver, Instant at) {
        return transition(RiskTicketStatus.FILING, approver, at, null, null, null, null);
    }

    RiskTicket filed(FiledIssue issue) {
        return transition(RiskTicketStatus.FILED, decidedBy, decidedAt, null, issue.key(), issue.url(), null);
    }

    /** An upstream error can quote the request it rejected, so it is redacted as it enters the record. */
    RiskTicket failed(String reason) {
        return transition(RiskTicketStatus.FAILED, decidedBy, decidedAt, null, null, null, SensitiveText.redact(reason));
    }

    RiskTicket dismissed(UUID by, Instant at, String reason) {
        return transition(RiskTicketStatus.DISMISSED, by, at, SensitiveText.redact(reason), null, null, failureMessage);
    }

    private RiskTicket transition(RiskTicketStatus status, UUID decidedBy, Instant decidedAt, String dismissReason,
            String issueKey, String issueUrl, String failureMessage) {
        return new RiskTicket(id, serviceId, changeSignalId, signalType, changeTitle, title, priority, dueDate, score,
                tier, labels, summary, risk, impact, actions, evidence, aiDrafted, citations, status, createdBy,
                createdAt, decidedBy, decidedAt, dismissReason, issueKey, issueUrl, failureMessage, edit, override,
                version);
    }
}
