package com.aegis.core.actions.upgrade;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Persistence port for upgrade proposals. Org-scoped like the inventory the proposals point into:
 * every read is limited to the caller's active organization and every insert is stamped with it.
 */
public interface UpgradeProposalStore {

    /** Insert a new proposal in the caller's organization. */
    UpgradeProposal create(UpgradeProposal proposal);

    Optional<UpgradeProposal> find(UUID id);

    /** The live (proposed, opening or opened) proposal for this service, change and component, if any. */
    Optional<UpgradeProposal> findLive(UUID serviceId, UUID changeSignalId, UUID componentId);

    /** Any filter may be null (or empty, for {@code statuses}): no restriction on that field. */
    Page<UpgradeProposal> findPage(
            UUID serviceId, UUID changeSignalId, Collection<UpgradeProposalStatus> statuses, Pageable pageable);

    /**
     * Persist a state change. {@code ApiExceptions.Conflict} if the proposal changed after it was read,
     * so two concurrent decisions never both win.
     */
    UpgradeProposal update(UpgradeProposal proposal);
}
