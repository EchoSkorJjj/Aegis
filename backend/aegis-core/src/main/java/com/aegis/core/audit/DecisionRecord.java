package com.aegis.core.audit;

import java.time.Instant;
import java.util.UUID;

import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.fasterxml.jackson.annotation.JsonRawValue;

/**
 * One step in the decision audit trail (E10 story 1, V36): who took it on which response, when, why, and the
 * response as it stood at that moment ({@code state}, JSON). Records are never changed or removed; the database
 * refuses. {@code sequence} orders them, also within one instant.
 *
 * <p>{@code responseId} groups a response's records: a risk ticket and its upgrade pull requests share the ticket's
 * id, a pull request drafted on its own and a memo use their own. The item a record belongs to is its change in its
 * service; a memo covers every service a change affects, so it names none. The service's name, the change's title,
 * the score and the tier are copies, as they were then.
 */
public record DecisionRecord(
        UUID id,
        long sequence,
        Instant occurredAt,
        Subject subject,
        UUID subjectId,
        UUID responseId,
        UUID serviceId,
        String serviceName,
        UUID changeSignalId,
        String changeTitle,
        Action action,
        UUID actorId,
        String reason,
        Double score,
        ImpactTier tier,
        @JsonRawValue String state) {

    /** What was decided on. */
    public enum Subject {
        RISK_TICKET,
        UPGRADE_PULL_REQUEST,
        REGULATOR_MEMO
    }

    /** The step taken. Outcomes (filed, opened, failed) are recorded under the approver who caused them. */
    public enum Action {
        DRAFTED,
        EDITED,
        OVERRIDDEN,
        OVERRIDE_WITHDRAWN,
        APPROVED,
        FILED,
        FILING_FAILED,
        OPENED,
        OPENING_FAILED,
        DISMISSED
    }

    /**
     * A step to record. {@code state} is the response itself (a ticket, a pull request, a memo), copied as JSON when
     * the step is recorded.
     */
    public record Entry(Subject subject, UUID subjectId, UUID responseId, UUID serviceId, String serviceName,
                        UUID changeSignalId, String changeTitle, Action action, UUID actorId, String reason,
                        Double score, ImpactTier tier, Object state) {
    }
}
