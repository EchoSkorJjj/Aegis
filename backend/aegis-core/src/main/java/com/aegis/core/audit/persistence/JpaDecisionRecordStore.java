package com.aegis.core.audit.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.audit.DecisionRecord;
import com.aegis.core.audit.DecisionRecordStore;
import com.aegis.core.audit.DecisionTrailQuery;
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
    public Page<DecisionRecord> findPage(DecisionTrailQuery filter, Pageable pageable) {
        UUID org = scope();
        List<Specification<DecisionRecordEntity>> specs = new ArrayList<>();
        specs.add((root, query, cb) -> cb.equal(root.get("orgId"), org));
        if (filter.serviceId() != null) {
            // Every record covering the service: its tickets and pull requests, and the memos that cover it.
            specs.add((root, query, cb) -> ((HibernateCriteriaBuilder) cb)
                    .arrayContains(root.<UUID[]>get("serviceIds"), filter.serviceId()));
        }
        if (filter.changeSignalId() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("changeSignalId"), filter.changeSignalId()));
        }
        if (filter.responseId() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("responseId"), filter.responseId()));
        }
        if (filter.actorId() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("actorId"), filter.actorId()));
        }
        if (!filter.actions().isEmpty()) {
            specs.add((root, query, cb) -> root.get("action").in(filter.actions()));
        }
        if (!filter.subjects().isEmpty()) {
            specs.add((root, query, cb) -> root.get("subject").in(filter.subjects()));
        }
        if (filter.from() != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from()));
        }
        if (filter.to() != null) {
            specs.add((root, query, cb) -> cb.lessThan(root.get("occurredAt"), filter.to()));
        }
        Page<DecisionRecordEntity> page = records.findAll(Specification.allOf(specs), pageable);
        Page<DecisionRecord> read = page.map(DecisionRecordEntity::toDomain);
        // Records are never changed, so nothing is lost by letting go of what was read: an export reads many pages,
        // snapshots and all, in one transaction.
        page.getContent().forEach(entityManager::detach);
        return read;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> servicesCovered() {
        UUID org = scope();
        List<?> rows = entityManager.createNativeQuery("""
                        select covered.id,
                               (select r.service_name from core.decision_records r
                                 where r.org_id = :org and r.service_id = covered.id and r.service_name is not null
                                 order by r.seq desc limit 1)
                          from (select distinct unnest(service_ids) as id from core.decision_records
                                 where org_id = :org) covered
                        """)
                .setParameter("org", org)
                .getResultList();
        Map<UUID, String> services = new LinkedHashMap<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            services.put((UUID) columns[0], (String) columns[1]);
        }
        return services;
    }

    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString())
                .getSingleResult();
        return org;
    }
}
