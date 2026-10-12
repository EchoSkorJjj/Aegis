package com.aegis.core.audit;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** The decision audit trail, scoped to the caller's organization (V36). Append and read only. */
public interface DecisionRecordStore {

    /** Append a record in the caller's organization; the database sets its time and sequence. */
    DecisionRecord append(DecisionRecord.Entry entry);

    /** One record, with its snapshot. */
    Optional<DecisionRecord> find(UUID id);

    /** The records matching {@code query}, in the order {@code pageable} sorts them ({@code sequence}). */
    Page<DecisionRecord> findPage(DecisionTrailQuery query, Pageable pageable);
}
