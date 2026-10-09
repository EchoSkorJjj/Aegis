package com.aegis.core.audit.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.aegis.core.audit.DecisionRecord;
import com.aegis.core.audit.DecisionRecord.Action;
import com.aegis.core.audit.DecisionRecord.Subject;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import org.hibernate.annotations.Generated;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * A decision record row (V36). Written once and never changed: no column is updatable, and the database refuses any
 * update or delete. The time and sequence are the database's, read back on insert. (Not Hibernate's
 * {@code @Immutable}: it can't read back what the database generated.)
 */
@Entity
@Table(name = "decision_records")
class DecisionRecordEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Generated
    @Column(name = "seq", insertable = false, updatable = false)
    private Long sequence;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Generated
    @Column(name = "occurred_at", insertable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false, length = 24)
    private Subject subject;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "response_id", nullable = false, updatable = false)
    private UUID responseId;

    @Column(name = "service_id", updatable = false)
    private UUID serviceId;

    @Column(name = "service_name", updatable = false)
    private String serviceName;

    @Column(name = "change_signal_id", nullable = false, updatable = false)
    private UUID changeSignalId;

    @Column(name = "change_title", nullable = false, updatable = false, length = 300)
    private String changeTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 24)
    private Action action;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "reason", updatable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "score", updatable = false)
    private Double score;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", updatable = false, length = 16)
    private ImpactTier tier;

    @Column(name = "state", nullable = false, updatable = false, columnDefinition = "text")
    private String state;

    protected DecisionRecordEntity() {
    }

    static DecisionRecordEntity create(UUID orgId, DecisionRecord.Entry entry, String state) {
        DecisionRecordEntity entity = new DecisionRecordEntity();
        entity.id = UUID.randomUUID();
        entity.orgId = orgId;
        entity.subject = entry.subject();
        entity.subjectId = entry.subjectId();
        entity.responseId = entry.responseId();
        entity.serviceId = entry.serviceId();
        entity.serviceName = entry.serviceName();
        entity.changeSignalId = entry.changeSignalId();
        entity.changeTitle = entry.changeTitle();
        entity.action = entry.action();
        entity.actorId = entry.actorId();
        entity.reason = entry.reason();
        entity.score = entry.score();
        entity.tier = entry.tier();
        entity.state = state;
        return entity;
    }

    DecisionRecord toDomain() {
        return new DecisionRecord(id, sequence, occurredAt, subject, subjectId, responseId, serviceId, serviceName,
                changeSignalId, changeTitle, action, actorId, reason, score, tier, state);
    }
}

interface DecisionRecordJpaRepository
        extends JpaRepository<DecisionRecordEntity, UUID>, JpaSpecificationExecutor<DecisionRecordEntity> {
}
