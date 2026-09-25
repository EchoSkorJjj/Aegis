package com.aegis.core.actions.upgrade.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.actions.upgrade.UpgradeProposal;
import com.aegis.core.actions.upgrade.UpgradeProposalStatus;
import com.aegis.core.actions.upgrade.UpgradeProposalStore;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link UpgradeProposalStore}. Every method resolves the caller's org through
 * {@link #scope()} — which also binds it on the database session for the row-level-security backstop
 * (V18, as V9 does for the inventory) — then reads only that org's rows and stamps it on inserts.
 */
@Repository
class JpaUpgradeProposalStore implements UpgradeProposalStore {

    private static final List<UpgradeProposalStatus> LIVE =
            List.of(UpgradeProposalStatus.PROPOSED, UpgradeProposalStatus.OPENING, UpgradeProposalStatus.OPENED);

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentOrg currentOrg;
    private final UpgradeProposalJpaRepository proposals;

    JpaUpgradeProposalStore(CurrentOrg currentOrg, UpgradeProposalJpaRepository proposals) {
        this.currentOrg = currentOrg;
        this.proposals = proposals;
    }

    @Override
    @Transactional
    public UpgradeProposal create(UpgradeProposal proposal) {
        return proposals.saveAndFlush(UpgradeProposalEntity.create(scope(), proposal)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UpgradeProposal> find(UUID id) {
        return proposals.findByIdAndOrgId(id, scope()).map(UpgradeProposalEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UpgradeProposal> findLive(UUID serviceId, UUID changeSignalId, UUID componentId) {
        return proposals.findFirstByOrgIdAndServiceIdAndChangeSignalIdAndComponentIdAndStatusIn(
                scope(), serviceId, changeSignalId, componentId, LIVE).map(UpgradeProposalEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UpgradeProposal> findPage(
            UUID serviceId, UUID changeSignalId, Collection<UpgradeProposalStatus> statuses, Pageable pageable) {
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
        return proposals.findAll(Specification.allOf(specs), pageable).map(UpgradeProposalEntity::toDomain);
    }

    @Override
    @Transactional
    public UpgradeProposal update(UpgradeProposal proposal) {
        UpgradeProposalEntity entity = proposals.findByIdAndOrgId(proposal.id(), scope())
                .orElseThrow(() -> new ApiExceptions.NotFound("Upgrade proposal not found: " + proposal.id()));
        if (entity.getVersion() != proposal.version()) {
            throw changedMeanwhile();
        }
        entity.applyDecision(proposal);
        try {
            return proposals.saveAndFlush(entity).toDomain();
        } catch (OptimisticLockingFailureException concurrentDecision) {
            throw changedMeanwhile();
        }
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

    Optional<UpgradeProposalEntity> findFirstByOrgIdAndServiceIdAndChangeSignalIdAndComponentIdAndStatusIn(
            UUID orgId, UUID serviceId, UUID changeSignalId, UUID componentId, List<UpgradeProposalStatus> statuses);
}
