package com.aegis.core.audit.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.audit.DecisionRecord;
import com.aegis.core.audit.DecisionRecordStore;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * JPA adapter for {@link DecisionRecordStore}. Every method resolves the caller's org through {@link #scope()} —
 * which also binds it on the database session for the row-level-security backstop (V36) — then reads only that
 * org's rows and stamps it on inserts. There is no update or delete.
 */
@Repository
class JpaDecisionRecordStore implements DecisionRecordStore {

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentOrg currentOrg;
    private final DecisionRecordJpaRepository records;
    private final ObjectMapper mapper;

    JpaDecisionRecordStore(CurrentOrg currentOrg, DecisionRecordJpaRepository records, ObjectMapper mapper) {
        this.currentOrg = currentOrg;
        this.records = records;
        this.mapper = mapper;
    }

    /** Persisted, not saved: a new record with its own id is inserted outright, without looking it up first. */
    @Override
    @Transactional
    public DecisionRecord append(DecisionRecord.Entry entry) {
        DecisionRecordEntity record = DecisionRecordEntity.create(scope(), entry,
                mapper.writeValueAsString(entry.state()));
        entityManager.persist(record);
        entityManager.flush();
        return record.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DecisionRecord> find(UUID id) {
        return records.findByIdAndOrgId(id, scope()).map(DecisionRecordEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DecisionRecord> findPage(UUID serviceId, UUID changeSignalId, UUID responseId, Pageable pageable) {
        UUID org = scope();
        List<Specification<DecisionRecordEntity>> specs = new ArrayList<>();
        specs.add((root, query, cb) -> cb.equal(root.get("orgId"), org));
        if (serviceId != null) {
            // Every record covering the service: its tickets and pull requests, and the memos that cover it.
            specs.add((root, query, cb) ->
                    ((HibernateCriteriaBuilder) cb).arrayContains(root.<UUID[]>get("serviceIds"), serviceId));
        }
        if (changeSignalId != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("changeSignalId"), changeSignalId));
        }
        if (responseId != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("responseId"), responseId));
        }
        return records.findAll(Specification.allOf(specs), pageable).map(DecisionRecordEntity::toDomain);
    }

    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString())
                .getSingleResult();
        return org;
    }
}
