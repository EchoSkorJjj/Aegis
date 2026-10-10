package com.aegis.core.audit;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import com.aegis.core.platform.common.ApiExceptions;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The decision audit trail (E10 story 1): every step a response takes, recorded with the response as it stood then.
 *
 * <p>Unlike identity-service's security log, which is written best-effort in a transaction of its own, a step and
 * its record commit together or not at all ({@link #recorded}): a decision with no record would be a gap in what
 * the trail must prove, and a record of a step that rolled back would be false. So a failure to record fails the
 * step. And a response is only ever saved as such a step: the response stores refuse a write made outside
 * {@link #recorded} ({@link #requireRecording}), so a new path that forgets the trail fails instead of leaving a gap.
 */
@Service
public class DecisionTrail {

    /** How many {@link #recorded} steps this thread is inside: a step may draft another (a ticket, its PR). */
    private static final ThreadLocal<Integer> RECORDING = ThreadLocal.withInitial(() -> 0);

    private final DecisionRecordStore store;

    public DecisionTrail(DecisionRecordStore store) {
        this.store = store;
    }

    /**
     * Take a step and record it in one transaction: {@code step} persists it (the stores' own transactions join this
     * one), then {@code entry} describes what it saved.
     */
    @Transactional
    public <T> T recorded(Supplier<T> step, Function<? super T, DecisionRecord.Entry> entry) {
        RECORDING.set(RECORDING.get() + 1);
        try {
            T saved = step.get();
            store.append(entry.apply(saved));
            return saved;
        } finally {
            if (RECORDING.get() == 1) {
                RECORDING.remove();
            } else {
                RECORDING.set(RECORDING.get() - 1);
            }
        }
    }

    /**
     * For the response stores (risk tickets, upgrade pull requests, regulator memos): refuse to save {@code what}
     * unless it is a step being {@linkplain #recorded recorded}.
     */
    public static void requireRecording(String what) {
        if (RECORDING.get() == 0) {
            throw new UnrecordedStep(what + " is saved only as a step of the decision trail "
                    + "(DecisionTrail.recorded), so that every decision is on record");
        }
    }

    /**
     * A response saved outside the trail: a programming error. Its own type, not an {@code IllegalStateException},
     * which Spring would translate into a data-access error on its way out of a repository.
     */
    public static class UnrecordedStep extends RuntimeException {
        UnrecordedStep(String message) {
            super(message);
        }
    }

    /** The trail of one item, response or service, oldest first, each record without its snapshot. */
    public Page<DecisionRecord> list(UUID serviceId, UUID changeSignalId, UUID responseId, Pageable pageable) {
        return store.findPage(serviceId, changeSignalId, responseId, pageable).map(DecisionRecord::withoutState);
    }

    /** One record with the response as it stood then. */
    public DecisionRecord get(UUID id) {
        return store.find(id).orElseThrow(() -> new ApiExceptions.NotFound("Decision record not found: " + id));
    }
}
