package com.aegis.core.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;

/**
 * One step in the decision audit trail (E10 story 1, V36): who took it on which response, when, why, and the
 * response as it stood at that moment ({@code state}, JSON). Records are never changed or removed; the database
 * refuses. {@code sequence} orders them, also within one instant.
 *
 * <p>{@code responseId} groups a response's records: a risk ticket and its upgrade pull requests share the ticket's
 * id, a pull request drafted on its own and a memo use their own. {@code serviceIds} are the services the step
 * covers: a ticket's or pull request's one service (also {@code serviceId}, with its name), or every service a
 * regulator memo covers, so a memo is in each of their trails though it names no single service (its
 * {@code serviceId} and {@code serviceName} are null). The service's name, the change's title, the score and the tier
 * are copies, as they were then; a memo's score and tier are its most affected service's. {@code state} is left out of
 * listings ({@link #withoutState}) and read one record at a time.
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
        List<UUID> serviceIds,
        UUID changeSignalId,
        String changeTitle,
        Action action,
        UUID actorId,
        String reason,
        Double score,
        ImpactTier tier,
        @JsonRawValue @JsonInclude(JsonInclude.Include.NON_NULL) String state) {

    public DecisionRecord {
        serviceIds = serviceIds == null ? List.of() : List.copyOf(serviceIds);
    }

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

    /** The record without its snapshot, as a listing shows it. */
    public DecisionRecord withoutState() {
        return new DecisionRecord(id, sequence, occurredAt, subject, subjectId, responseId, serviceId, serviceName,
                serviceIds, changeSignalId, changeTitle, action, actorId, reason, score, tier, null);
    }

    /**
     * A step to record. {@code state} is the response itself (a ticket, a pull request, a memo), copied as JSON when
     * the step is recorded. {@code serviceIds} are the services it covers; for a ticket or a pull request, its own.
     */
    public record Entry(Subject subject, UUID subjectId, UUID responseId, UUID serviceId, String serviceName,
                        List<UUID> serviceIds, UUID changeSignalId, String changeTitle, Action action, UUID actorId,
                        String reason, Double score, ImpactTier tier, Object state) {

        public Entry {
            serviceIds = serviceIds == null ? List.of() : List.copyOf(serviceIds);
        }
    }
}
