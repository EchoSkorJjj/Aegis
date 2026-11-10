package com.aegis.core.actions.triage.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.actions.triage.FindingInterpretation;
import com.aegis.core.actions.triage.FindingInterpretationStore;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link FindingInterpretationStore}. Every method resolves the caller's org through {@link #scope()},
 * which also binds it on the database session for the row-level-security backstop (V46), then touches only that org's
 * rows and stamps it on inserts.
 */
@Repository
class JpaFindingInterpretationStore implements FindingInterpretationStore {

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentOrg currentOrg;
    private final FindingInterpretationJpaRepository interpretations;

    JpaFindingInterpretationStore(CurrentOrg currentOrg, FindingInterpretationJpaRepository interpretations) {
        this.currentOrg = currentOrg;
        this.interpretations = interpretations;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FindingInterpretation> findForServices(Collection<UUID> serviceIds) {
        if (serviceIds.isEmpty()) {
            return List.of();
        }
        return interpretations.findForServices(scope(), serviceIds).stream()
                .map(FindingInterpretationEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FindingInterpretation> findForChange(UUID changeSignalId) {
        return interpretations.findForChange(scope(), changeSignalId).stream()
                .map(FindingInterpretationEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public FindingInterpretation save(FindingInterpretation saved) {
        interpretations.upsert(saved.id(), scope(), saved.serviceId(), saved.itemKey(), saved.changeSignalId(),
                saved.subject(), saved.signal().name(), saved.confidence().name(), saved.whatChanged(),
                saved.whyItMatters(), saved.whatNext(), saved.suggestedAction().name(), saved.actionDetail(), saved.condition(),
                saved.conditionMet().name(),
                saved.assumptions().toArray(String[]::new), saved.citations().toArray(String[]::new), saved.fingerprint(),
                saved.interpretedAt());
        return saved;
    }

    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString())
                .getSingleResult();
        return org;
    }
}
