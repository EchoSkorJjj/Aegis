package com.aegis.core.actions.memo.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.actions.memo.RegulatorMemo;
import com.aegis.core.actions.memo.RegulatorMemo.AffectedService;
import com.aegis.core.actions.memo.RegulatorMemoStatus;
import com.aegis.core.actions.memo.RegulatorMemoStore;
import com.aegis.core.audit.DecisionTrail;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * JPA adapter for {@link RegulatorMemoStore}. Every method resolves the caller's org through {@link #scope()}
 * — which also binds it on the database session for the row-level-security backstop (V25) — then reads only
 * that org's rows and stamps it on inserts.
 */
@Repository
class JpaRegulatorMemoStore implements RegulatorMemoStore {

    private static final List<RegulatorMemoStatus> LIVE = List.of(RegulatorMemoStatus.PROPOSED, RegulatorMemoStatus.APPROVED);
    private static final TypeReference<List<AffectedService>> SERVICES = new TypeReference<>() { };

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentOrg currentOrg;
    private final RegulatorMemoJpaRepository memos;
    private final ObjectMapper mapper;

    JpaRegulatorMemoStore(CurrentOrg currentOrg, RegulatorMemoJpaRepository memos, ObjectMapper mapper) {
        this.currentOrg = currentOrg;
        this.memos = memos;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public RegulatorMemo create(RegulatorMemo memo) {
        DecisionTrail.requireRecording("A regulator memo");
        RegulatorMemoEntity saved = memos.saveAndFlush(
                RegulatorMemoEntity.create(scope(), memo, mapper.writeValueAsString(memo.services())));
        entityManager.refresh(saved); // created_at is the database's
        return domain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegulatorMemo> find(UUID id) {
        return memos.findByIdAndOrgId(id, scope()).map(this::domain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegulatorMemo> findLive(UUID changeSignalId) {
        return memos.findFirstByOrgIdAndChangeSignalIdAndStatusIn(scope(), changeSignalId, LIVE).map(this::domain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RegulatorMemo> findPage(UUID changeSignalId, Collection<RegulatorMemoStatus> statuses, Pageable pageable) {
        UUID org = scope();
        List<Specification<RegulatorMemoEntity>> specs = new ArrayList<>();
        specs.add((root, query, cb) -> cb.equal(root.get("orgId"), org));
        if (changeSignalId != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("changeSignalId"), changeSignalId));
        }
        if (statuses != null && !statuses.isEmpty()) {
            specs.add((root, query, cb) -> root.get("status").in(statuses));
        }
        return memos.findAll(Specification.allOf(specs), pageable).map(this::domain);
    }

    @Override
    @Transactional
    public RegulatorMemo update(RegulatorMemo memo) {
        DecisionTrail.requireRecording("A regulator memo");
        RegulatorMemoEntity entity = memos.findForUpdateByIdAndOrgId(memo.id(), scope())
                .orElseThrow(() -> new ApiExceptions.NotFound("Regulator memo not found: " + memo.id()));
        if (entity.getVersion() != memo.version()) {
            throw changedMeanwhile();
        }
        entity.applyDecision(memo);
        try {
            return domain(memos.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException concurrentDecision) {
            throw changedMeanwhile();
        }
    }

    private RegulatorMemo domain(RegulatorMemoEntity entity) {
        return entity.toDomain(mapper.readValue(entity.services(), SERVICES));
    }

    private static ApiExceptions.Conflict changedMeanwhile() {
        return new ApiExceptions.Conflict("The regulator memo was changed by someone else; reload it and decide again");
    }

    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString())
                .getSingleResult();
        return org;
    }
}
