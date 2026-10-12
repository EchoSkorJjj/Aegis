package com.aegis.core.audit;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.audit.DecisionRecord.Action;
import com.aegis.core.audit.DecisionRecord.Subject;
import com.aegis.core.platform.common.ApiExceptions;

/**
 * What to search the decision trail for (E10 story 2). Every field may be null (or empty): no restriction on it; the
 * rest are ANDed. A service matches every record that covers it, a regulator memo's included. {@code actions} and
 * {@code subjects} match any of theirs. The period is half-open, {@code from} inclusive and {@code to} exclusive, so
 * consecutive periods (one day, the next) never count a record twice.
 */
public record DecisionTrailQuery(
        UUID serviceId,
        UUID changeSignalId,
        UUID responseId,
        UUID actorId,
        Set<Action> actions,
        Set<Subject> subjects,
        Instant from,
        Instant to) {

    public DecisionTrailQuery {
        actions = actions == null ? Set.of() : Set.copyOf(actions);
        subjects = subjects == null ? Set.of() : Set.copyOf(subjects);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ApiExceptions.BadRequest("The period's start (from) must be before its end (to)");
        }
    }
}
