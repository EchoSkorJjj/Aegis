package com.aegis.core.actions.memo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.platform.common.SensitiveText;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.signals.ChangeSignal.SignalType;

/**
 * A drafted DORA notification about one change (E8 story 6): what an analyst reviews before it is final and
 * a person submits it to the competent authority. The reference, timeline, affected services and evidence
 * are Aegis's own; the summary, impact, measures and next steps are the AI's draft ({@code aiDrafted}), or
 * Aegis's plain fallback. {@code body} is the whole memo as rendered from its template. Aegis never sends it.
 *
 * <p>{@code version} is the optimistic lock that makes two people deciding at once safe.
 */
public record RegulatorMemo(
        UUID id,
        UUID changeSignalId,
        SignalType signalType,
        String changeTitle,
        MemoKind kind,
        String reference,
        Instant detectedAt,
        List<AffectedService> services,
        String summary,
        String impact,
        String measures,
        String nextSteps,
        boolean aiDrafted,
        List<String> citations,
        String body,
        RegulatorMemoStatus status,
        UUID createdBy,
        Instant createdAt,
        UUID decidedBy,
        Instant decidedAt,
        String dismissReason,
        long version) {

    public RegulatorMemo {
        services = List.copyOf(services);
        citations = List.copyOf(citations);
    }

    /** A service the change affects, as Aegis scored it when the memo was drafted. */
    public record AffectedService(UUID serviceId, String name, BusinessCriticality criticality,
                                  ServiceExposure exposure, double score, ImpactTier tier, boolean material) {
    }

    RegulatorMemo approved(UUID by, Instant at) {
        return decided(RegulatorMemoStatus.APPROVED, by, at, null);
    }

    RegulatorMemo dismissed(UUID by, Instant at, String reason) {
        return decided(RegulatorMemoStatus.DISMISSED, by, at, SensitiveText.redact(reason));
    }

    private RegulatorMemo decided(RegulatorMemoStatus status, UUID by, Instant at, String reason) {
        return new RegulatorMemo(id, changeSignalId, signalType, changeTitle, kind, reference, detectedAt, services,
                summary, impact, measures, nextSteps, aiDrafted, citations, body, status, createdBy, createdAt, by, at,
                reason, version);
    }
}
