package com.aegis.core.actions.ticket;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Risk tickets, scoped to the caller's organization (V23). Every write is a step of the decision trail: inside
 * {@code DecisionTrail.recorded}, or refused.
 */
public interface RiskTicketStore {

    /** Responses in Review & approve, including tickets whose pull requests still need attention. */
    long countPending();

    /** Insert a new ticket in the caller's organization. */
    RiskTicket create(RiskTicket ticket);

    Optional<RiskTicket> find(UUID id);

    /** The current (not dismissed) ticket for this service and change, if any. */
    Optional<RiskTicket> findLive(UUID serviceId, UUID changeSignalId);

    /** Every current (not dismissed) ticket of these services, newest first. */
    List<RiskTicket> findCurrent(Collection<UUID> serviceIds);

    /** Every dismissed ticket of these services, most recently dismissed first. */
    List<RiskTicket> findDismissed(Collection<UUID> serviceIds);

    /**
     * Any filter may be null (or empty, for {@code statuses}): no restriction on that field. {@code pending}
     * true keeps the responses that still need a person — the ticket awaits a decision, is being filed or
     * failed, or its pull request does — and false the ones that don't.
     */
    Page<RiskTicket> findPage(UUID serviceId, UUID changeSignalId, Collection<RiskTicketStatus> statuses,
            Boolean pending, Pageable pageable);

    /**
     * Persist a decision, an edit or an override. {@code ApiExceptions.Conflict} if the ticket changed after it was
     * read, so two concurrent decisions never both win.
     */
    RiskTicket update(RiskTicket ticket);
}
