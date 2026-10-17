package com.aegis.core.actions.memo;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Regulator-notification memos, scoped to the caller's organization (V25). Every write is a step of the decision
 * trail: inside {@code DecisionTrail.recorded}, or refused.
 */
public interface RegulatorMemoStore {

    /** Insert a new memo in the caller's organization. */
    RegulatorMemo create(RegulatorMemo memo);

    Optional<RegulatorMemo> find(UUID id);

    /** The live (proposed or approved) memo for this change, if any. */
    Optional<RegulatorMemo> findLive(UUID changeSignalId);

    /** Any filter may be null (or empty, for {@code statuses}): no restriction on that field. */
    Page<RegulatorMemo> findPage(UUID changeSignalId, Collection<RegulatorMemoStatus> statuses, Pageable pageable);

    /**
     * Persist a decision. {@code ApiExceptions.Conflict} if the memo changed after it was read, so two
     * concurrent decisions never both win.
     */
    RegulatorMemo update(RegulatorMemo memo);
}
