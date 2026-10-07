package com.aegis.core.actions.ticket.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.actions.ticket.RiskTicket;
import com.aegis.core.actions.ticket.RiskTicketStatus;
import com.aegis.core.actions.ticket.RiskTicketStore;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link RiskTicketStore}. Every method resolves the caller's org through {@link #scope()} —
 * which also binds it on the database session for the row-level-security backstop (V23) — then reads only
 * that org's rows and stamps it on inserts.
 */
@Repository
class JpaRiskTicketStore implements RiskTicketStore {

    private static final List<RiskTicketStatus> LIVE = List.of(RiskTicketStatus.PROPOSED, RiskTicketStatus.FILING,
            RiskTicketStatus.FILED, RiskTicketStatus.FAILED);
    private static final List<RiskTicketStatus> UNDECIDED =
            List.of(RiskTicketStatus.PROPOSED, RiskTicketStatus.FILING, RiskTicketStatus.FAILED);

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentOrg currentOrg;
    private final RiskTicketJpaRepository tickets;

    JpaRiskTicketStore(CurrentOrg currentOrg, RiskTicketJpaRepository tickets) {
        this.currentOrg = currentOrg;
        this.tickets = tickets;
    }

    @Override
    @Transactional
    public RiskTicket create(RiskTicket ticket) {
        RiskTicketEntity saved = tickets.saveAndFlush(RiskTicketEntity.create(scope(), ticket));
        entityManager.refresh(saved); // created_at is the database's
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RiskTicket> find(UUID id) {
        return tickets.findByIdAndOrgId(id, scope()).map(RiskTicketEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RiskTicket> findLive(UUID serviceId, UUID changeSignalId) {
        return tickets.findFirstByOrgIdAndServiceIdAndChangeSignalIdAndStatusIn(scope(), serviceId, changeSignalId, LIVE)
                .map(RiskTicketEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RiskTicket> findCurrent(Collection<UUID> serviceIds) {
        if (serviceIds.isEmpty()) {
            return List.of();
        }
        return tickets.findByOrgIdAndServiceIdInAndStatusIn(scope(), serviceIds, LIVE,
                Sort.by(Sort.Direction.DESC, "createdAt")).stream().map(RiskTicketEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RiskTicket> findPage(UUID serviceId, UUID changeSignalId, Collection<RiskTicketStatus> statuses,
            Boolean pending, Pageable pageable) {
        UUID org = scope();
        List<Specification<RiskTicketEntity>> specs = new ArrayList<>();
        specs.add((root, query, cb) -> cb.equal(root.get("orgId"), org));
        if (serviceId != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("serviceId"), serviceId));
        }
        if (changeSignalId != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("changeSignalId"), changeSignalId));
        }
        if (statuses != null && !statuses.isEmpty()) {
            specs.add((root, query, cb) -> root.get("status").in(statuses));
        }
        if (pending != null) {
            specs.add((root, query, cb) -> {
                var waiting = cb.or(root.get("status").in(UNDECIDED),
                        cb.greaterThan(root.<Integer>get("pendingPullRequests"), 0));
                return pending ? waiting : cb.not(waiting);
            });
        }
        return tickets.findAll(Specification.allOf(specs), pageable).map(RiskTicketEntity::toDomain);
    }

    @Override
    @Transactional
    public RiskTicket update(RiskTicket ticket) {
        RiskTicketEntity entity = tickets.findForUpdateByIdAndOrgId(ticket.id(), scope())
                .orElseThrow(() -> new ApiExceptions.NotFound("Risk ticket not found: " + ticket.id()));
        if (entity.getVersion() != ticket.version()) {
            throw changedMeanwhile();
        }
        entity.apply(ticket);
        try {
            return tickets.saveAndFlush(entity).toDomain();
        } catch (OptimisticLockingFailureException concurrentDecision) {
            throw changedMeanwhile();
        }
    }

    private static ApiExceptions.Conflict changedMeanwhile() {
        return new ApiExceptions.Conflict("The risk ticket was changed by someone else; reload it and decide again");
    }

    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString())
                .getSingleResult();
        return org;
    }
}
