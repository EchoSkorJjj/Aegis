package com.aegis.core.actions.upgrade.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.actions.upgrade.UpgradeProposal;
import com.aegis.core.actions.upgrade.UpgradeProposalStatus;
import com.aegis.core.actions.upgrade.UpgradeProposalStore;
import com.aegis.core.audit.DecisionTrail;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * JPA adapter for {@link UpgradeProposalStore}. Every method resolves the caller's org through
 * {@link #scope()} — which also binds it on the database session for the row-level-security backstop
 * (V18, as V9 does for the inventory) — then reads only that org's rows and stamps it on inserts.
 */
@Repository
class JpaUpgradeProposalStore implements UpgradeProposalStore {

    private static final List<UpgradeProposalStatus> CURRENT = List.of(UpgradeProposalStatus.PROPOSED,
            UpgradeProposalStatus.OPENING, UpgradeProposalStatus.OPENED, UpgradeProposalStatus.FAILED);
    private static final TypeReference<List<UpgradeProposal.Edit>> EDITS = new TypeReference<>() { };

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentOrg currentOrg;
    private final UpgradeProposalJpaRepository proposals;
    private final ObjectMapper mapper;

    JpaUpgradeProposalStore(CurrentOrg currentOrg, UpgradeProposalJpaRepository proposals, ObjectMapper mapper) {
        this.currentOrg = currentOrg;
        this.proposals = proposals;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public UpgradeProposal create(UpgradeProposal proposal) {
        DecisionTrail.requireRecording("An upgrade pull request");
        return domain(proposals.saveAndFlush(
                UpgradeProposalEntity.create(scope(), proposal, mapper.writeValueAsString(proposal.edits()))));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UpgradeProposal> find(UUID id) {
        return proposals.findByIdAndOrgId(id, scope()).map(this::domain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UpgradeProposal> findCurrent(UUID serviceId) {
        return proposals.findByOrgIdAndServiceIdAndStatusIn(scope(), serviceId, CURRENT).stream()
                .map(this::domain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UpgradeProposal> findForTickets(Collection<UUID> riskTicketIds) {
        if (riskTicketIds.isEmpty()) {
            return List.of();
        }
        return proposals.findByOrgIdAndRiskTicketIdIn(scope(), riskTicketIds,
                        Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(this::domain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UpgradeProposal> findPage(UUID serviceId, UUID changeSignalId,
            Collection<UpgradeProposalStatus> statuses, boolean standaloneOnly, Pageable pageable) {
        UUID org = scope();
        List<Specification<UpgradeProposalEntity>> specs = new ArrayList<>();
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
        if (standaloneOnly) {
            specs.add((root, query, cb) -> cb.isNull(root.get("riskTicketId")));
        }
        return proposals.findAll(Specification.allOf(specs), pageable).map(this::domain);
    }

    @Override
    @Transactional
    public UpgradeProposal update(UpgradeProposal proposal) {
        DecisionTrail.requireRecording("An upgrade pull request");
        UpgradeProposalEntity entity = proposals.findForUpdateByIdAndOrgId(proposal.id(), scope())
                .orElseThrow(() -> new ApiExceptions.NotFound("Upgrade proposal not found: " + proposal.id()));
        if (entity.getVersion() != proposal.version()) {
            throw changedMeanwhile();
        }
        entity.applyDecision(proposal);
        try {
            return domain(proposals.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException concurrentDecision) {
            throw changedMeanwhile();
        }
    }

    private UpgradeProposal domain(UpgradeProposalEntity entity) {
        return entity.toDomain(mapper.readValue(entity.edits(), EDITS));
    }

    private static ApiExceptions.Conflict changedMeanwhile() {
        return new ApiExceptions.Conflict("The upgrade proposal was changed by someone else; reload it and decide again");
    }

    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString())
                .getSingleResult();
        return org;
    }
}

interface UpgradeProposalJpaRepository
        extends JpaRepository<UpgradeProposalEntity, UUID>, JpaSpecificationExecutor<UpgradeProposalEntity> {

    Optional<UpgradeProposalEntity> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The row, locked until the transaction ends ({@code select … for update}): a concurrent decision waits, then
     * sees the version this one wrote, so it is a conflict rather than a second outcome.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UpgradeProposalEntity> findForUpdateByIdAndOrgId(UUID id, UUID orgId);

    List<UpgradeProposalEntity> findByOrgIdAndServiceIdAndStatusIn(
            UUID orgId, UUID serviceId, List<UpgradeProposalStatus> statuses);

    List<UpgradeProposalEntity> findByOrgIdAndRiskTicketIdIn(UUID orgId, Collection<UUID> riskTicketIds, Sort sort);
}
