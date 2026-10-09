package com.aegis.core.audit;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

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
 * step.
 */
@Service
public class DecisionTrail {

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
        T saved = step.get();
        store.append(entry.apply(saved));
        return saved;
    }

    /** The trail of one item, response or service, oldest first. Any filter may be null. */
    public Page<DecisionRecord> list(UUID serviceId, UUID changeSignalId, UUID responseId, Pageable pageable) {
        return store.findPage(serviceId, changeSignalId, responseId, pageable);
    }
}
