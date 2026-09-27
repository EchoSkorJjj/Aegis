package com.aegis.core.actions.memo.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.aegis.core.actions.memo.MemoKind;
import com.aegis.core.actions.memo.RegulatorMemo;
import com.aegis.core.actions.memo.RegulatorMemo.AffectedService;
import com.aegis.core.actions.memo.RegulatorMemoStatus;
import com.aegis.core.signals.ChangeSignal.SignalType;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * A regulator memo row (V25). The drafted memo never changes; only the decision fields do. The affected
 * services are kept as JSON, a snapshot of how Aegis scored them when the memo was drafted.
 */
@Entity
@Table(name = "regulator_memos")
class RegulatorMemoEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "change_signal_id", nullable = false, updatable = false)
    private UUID changeSignalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, updatable = false, length = 16)
    private SignalType signalType;

    @Column(name = "change_title", nullable = false, updatable = false, length = 300)
    private String changeTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 24)
    private MemoKind kind;

    @Column(name = "reference", nullable = false, updatable = false, length = 32)
    private String reference;

    @Column(name = "detected_at", updatable = false)
    private Instant detectedAt;

    @Column(name = "services", nullable = false, updatable = false, columnDefinition = "text")
    private String services;

    @Column(name = "summary", nullable = false, updatable = false, columnDefinition = "text")
    private String summary;

    @Column(name = "impact", nullable = false, updatable = false, columnDefinition = "text")
    private String impact;

    @Column(name = "measures", nullable = false, updatable = false, columnDefinition = "text")
    private String measures;

    @Column(name = "next_steps", nullable = false, updatable = false, columnDefinition = "text")
    private String nextSteps;

    @Column(name = "ai_drafted", nullable = false, updatable = false)
    private boolean aiDrafted;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "citations", nullable = false, updatable = false, columnDefinition = "text[]")
    private String[] citations;

    @Column(name = "body", nullable = false, updatable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RegulatorMemoStatus status;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "dismiss_reason", length = 1000)
    private String dismissReason;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected RegulatorMemoEntity() {
    }

    static RegulatorMemoEntity create(UUID orgId, RegulatorMemo memo, String servicesJson) {
        RegulatorMemoEntity entity = new RegulatorMemoEntity();
        entity.id = memo.id();
        entity.orgId = orgId;
        entity.changeSignalId = memo.changeSignalId();
        entity.signalType = memo.signalType();
        entity.changeTitle = memo.changeTitle();
        entity.kind = memo.kind();
        entity.reference = memo.reference();
        entity.detectedAt = memo.detectedAt();
        entity.services = servicesJson;
        entity.summary = memo.summary();
        entity.impact = memo.impact();
        entity.measures = memo.measures();
        entity.nextSteps = memo.nextSteps();
        entity.aiDrafted = memo.aiDrafted();
        entity.citations = memo.citations().toArray(String[]::new);
        entity.body = memo.body();
        entity.status = memo.status();
        entity.createdBy = memo.createdBy();
        return entity;
    }

    void applyDecision(RegulatorMemo memo) {
        this.status = memo.status();
        this.decidedBy = memo.decidedBy();
        this.decidedAt = memo.decidedAt();
        this.dismissReason = memo.dismissReason();
    }

    long getVersion() {
        return version;
    }

    String services() {
        return services;
    }

    RegulatorMemo toDomain(List<AffectedService> affected) {
        return new RegulatorMemo(id, changeSignalId, signalType, changeTitle, kind, reference, detectedAt, affected,
                summary, impact, measures, nextSteps, aiDrafted, List.of(citations), body, status, createdBy,
                createdAt, decidedBy, decidedAt, dismissReason, version);
    }
}

interface RegulatorMemoJpaRepository
        extends JpaRepository<RegulatorMemoEntity, UUID>, JpaSpecificationExecutor<RegulatorMemoEntity> {

    Optional<RegulatorMemoEntity> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<RegulatorMemoEntity> findFirstByOrgIdAndChangeSignalIdAndStatusIn(
            UUID orgId, UUID changeSignalId, List<RegulatorMemoStatus> statuses);
}
