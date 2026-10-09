package com.aegis.core.actions.upgrade;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Persistence port for upgrade proposals. Org-scoped like the inventory the proposals point into:
 * every read is limited to the caller's active organization and every insert is stamped with it. Every write is a
 * step of the decision trail: inside {@code DecisionTrail.recorded}, or refused.
 */
public interface UpgradeProposalStore {

    /** Insert a new proposal in the caller's organization. */
    UpgradeProposal create(UpgradeProposal proposal);

    Optional<UpgradeProposal> find(UUID id);

    /** The service's proposals that are not dismissed: awaiting a decision, opening, open, or failed. */
    List<UpgradeProposal> findCurrent(UUID serviceId);

    /** Every proposal of these responses (risk tickets), newest first. */
    List<UpgradeProposal> findForTickets(Collection<UUID> riskTicketIds);

    /**
     * Any filter may be null (or empty, for {@code statuses}): no restriction on that field. With
     * {@code standaloneOnly}, only proposals that belong to no response.
     */
    Page<UpgradeProposal> findPage(UUID serviceId, UUID changeSignalId, Collection<UpgradeProposalStatus> statuses,
            boolean standaloneOnly, Pageable pageable);

    /**
     * Persist a state change. {@code ApiExceptions.Conflict} if the proposal changed after it was read,
     * so two concurrent decisions never both win.
     */
    UpgradeProposal update(UpgradeProposal proposal);
}
