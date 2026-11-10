package com.aegis.core.actions.triage.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.aegis.core.actions.triage.FindingInterpretation;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The AI's reading of one material finding in one service (V46). */
@Entity
@Table(name = "finding_interpretations")
class FindingInterpretationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "business_service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "item_key", nullable = false, updatable = false, length = 512)
    private String itemKey;

    @Column(name = "change_signal_id", nullable = false)
    private UUID changeSignalId;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "signal", nullable = false, length = 16)
    private String signal;

    @Column(name = "confidence", nullable = false, length = 8)
    private String confidence;

    @Column(name = "what_changed", nullable = false, length = 2000)
    private String whatChanged;

    @Column(name = "why_it_matters", nullable = false, length = 2000)
    private String whyItMatters;

    @Column(name = "what_next", nullable = false, length = 2000)
    private String whatNext;

    @Column(name = "suggested_action", nullable = false, length = 16)
    private String suggestedAction;

    @Column(name = "action_detail", nullable = false, length = 1000)
    private String actionDetail;

    @Column(name = "trigger_condition", length = 1000)
    private String condition;

    @Column(name = "condition_met", nullable = false, length = 8)
    private String conditionMet;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "assumptions", nullable = false, columnDefinition = "text[]")
    private String[] assumptions = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "citations", nullable = false, columnDefinition = "text[]")
    private String[] citations = new String[0];

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "interpreted_at", nullable = false)
    private Instant interpretedAt;

    protected FindingInterpretationEntity() {
    }

    FindingInterpretation toDomain() {
        return new FindingInterpretation(id, serviceId, itemKey, changeSignalId, subject,
                FindingInterpretation.Signal.valueOf(signal), FindingInterpretation.Confidence.valueOf(confidence),
                whatChanged, whyItMatters, whatNext, FindingInterpretation.SuggestedAction.valueOf(suggestedAction),
                actionDetail, condition, FindingInterpretation.ConditionMet.valueOf(conditionMet), List.of(assumptions), List.of(citations), fingerprint, interpretedAt);
    }
}

interface FindingInterpretationJpaRepository extends JpaRepository<FindingInterpretationEntity, UUID> {

    @Query("select i from FindingInterpretationEntity i where i.orgId = :org and i.serviceId in :services")
    List<FindingInterpretationEntity> findForServices(@Param("org") UUID org, @Param("services") Collection<UUID> services);

    @Query("select i from FindingInterpretationEntity i where i.orgId = :org and i.changeSignalId = :change")
    List<FindingInterpretationEntity> findForChange(@Param("org") UUID org, @Param("change") UUID changeSignalId);

    /** Insert, or replace the reading of the same service and item. */
    @Modifying
    @Query(value = """
            insert into {h-schema}finding_interpretations (id, org_id, business_service_id, item_key, change_signal_id,
                subject, signal, confidence, what_changed, why_it_matters, what_next, suggested_action, action_detail,
                trigger_condition, condition_met, assumptions, citations, fingerprint, interpreted_at)
            values (:id, :org, :service, :itemKey, :change, :subject, :signal, :confidence, :whatChanged, :whyItMatters,
                :whatNext, :suggestedAction, :actionDetail, :condition, :conditionMet, cast(:assumptions as text[]), cast(:citations as text[]),
                :fingerprint, :interpretedAt)
            on conflict (business_service_id, item_key) do update
                set id = excluded.id, change_signal_id = excluded.change_signal_id, subject = excluded.subject,
                    signal = excluded.signal, confidence = excluded.confidence, what_changed = excluded.what_changed,
                    why_it_matters = excluded.why_it_matters, what_next = excluded.what_next,
                    suggested_action = excluded.suggested_action, action_detail = excluded.action_detail,
                    trigger_condition = excluded.trigger_condition, condition_met = excluded.condition_met,
                    assumptions = excluded.assumptions, citations = excluded.citations,
                    fingerprint = excluded.fingerprint, interpreted_at = excluded.interpreted_at
            """, nativeQuery = true)
    int upsert(@Param("id") UUID id, @Param("org") UUID org, @Param("service") UUID serviceId,
            @Param("itemKey") String itemKey, @Param("change") UUID changeSignalId, @Param("subject") String subject,
            @Param("signal") String signal, @Param("confidence") String confidence,
            @Param("whatChanged") String whatChanged, @Param("whyItMatters") String whyItMatters,
            @Param("whatNext") String whatNext, @Param("suggestedAction") String suggestedAction,
            @Param("actionDetail") String actionDetail, @Param("condition") String condition,
            @Param("conditionMet") String conditionMet, @Param("assumptions") String[] assumptions,
            @Param("citations") String[] citations, @Param("fingerprint") String fingerprint,
            @Param("interpretedAt") Instant interpretedAt);
}
