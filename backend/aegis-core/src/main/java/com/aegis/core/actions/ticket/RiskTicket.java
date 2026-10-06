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
        long version) {

    public RiskTicket {
        changeTitle = SensitiveText.redact(changeTitle);
        title = SensitiveText.redact(title);
        labels = List.copyOf(SensitiveText.redact(labels));
        summary = SensitiveText.redact(summary);
        risk = SensitiveText.redact(risk);
        impact = SensitiveText.redact(impact);
        actions = List.copyOf(SensitiveText.redact(actions));
        evidence = List.copyOf(SensitiveText.redact(evidence));
        citations = List.copyOf(SensitiveText.redact(citations));
        dismissReason = SensitiveText.redact(dismissReason);
        failureMessage = SensitiveText.redact(failureMessage);
    }

    /** Approved by {@code approver}; the issue is about to be filed. */
    RiskTicket filing(UUID approver, Instant at) {
        return transition(RiskTicketStatus.FILING, approver, at, null, null, null, null);
    }

    RiskTicket filed(FiledIssue issue) {
        return transition(RiskTicketStatus.FILED, decidedBy, decidedAt, null, issue.key(), issue.url(), null);
    }

    RiskTicket failed(String reason) {
        return transition(RiskTicketStatus.FAILED, decidedBy, decidedAt, null, null, null, reason);
    }

    RiskTicket dismissed(UUID by, Instant at, String reason) {
        return transition(RiskTicketStatus.DISMISSED, by, at, reason, null, null, failureMessage);
    }

    private RiskTicket transition(RiskTicketStatus status, UUID decidedBy, Instant decidedAt, String dismissReason,
            String issueKey, String issueUrl, String failureMessage) {
        return new RiskTicket(id, serviceId, changeSignalId, signalType, changeTitle, title, priority, dueDate, score,
                tier, labels, summary, risk, impact, actions, evidence, aiDrafted, citations, status, createdBy,
                createdAt, decidedBy, decidedAt, dismissReason, issueKey, issueUrl, failureMessage, version);
    }
}
