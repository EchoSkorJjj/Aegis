package com.aegis.core.actions.assess.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.actions.assess.ReachabilityAssessment;
import com.aegis.core.actions.assess.ReachabilityAssessmentStore;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link ReachabilityAssessmentStore}. Every method resolves the caller's org through {@link #scope()}
 * — which also binds it on the database session for the row-level-security backstop (V41) — then touches only that
 * org's rows and stamps it on inserts.
 */
@Repository
class JpaReachabilityAssessmentStore implements ReachabilityAssessmentStore {

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentOrg currentOrg;
    private final ReachabilityAssessmentJpaRepository assessments;

    JpaReachabilityAssessmentStore(CurrentOrg currentOrg, ReachabilityAssessmentJpaRepository assessments) {
        this.currentOrg = currentOrg;
        this.assessments = assessments;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReachabilityAssessment> findFor(Collection<UUID> serviceIds, String vulnerabilityId) {
        if (serviceIds.isEmpty() || vulnerabilityId == null) {
            return List.of();
        }
        return assessments.findFor(scope(), serviceIds, vulnerabilityId).stream()
                .map(ReachabilityAssessmentEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public ReachabilityAssessment save(ReachabilityAssessment assessment) {
        assessments.upsert(assessment.id(), scope(), assessment.serviceId(), assessment.ecosystem(),
                assessment.packageName(), assessment.vulnerabilityId(), assessment.revision(),
                assessment.verdict().name(), assessment.rationale(),
                assessment.cited().stream().map(ReachabilityAssessment.Cited::encode).toArray(String[]::new),
                assessment.checks().toArray(String[]::new),
                assessment.searches().stream().map(ReachabilityAssessment.Searched::encode).toArray(String[]::new),
                assessment.requestedBy(), assessment.assessedAt());
        return assessment;
    }

    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString())
                .getSingleResult();
        return org;
    }
}
