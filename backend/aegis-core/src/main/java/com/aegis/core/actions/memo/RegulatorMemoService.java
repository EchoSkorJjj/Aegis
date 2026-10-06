package com.aegis.core.actions.memo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.actions.evidence.DraftEvidence;
import com.aegis.core.actions.memo.MemoTemplate.Facts;
import com.aegis.core.actions.memo.MemoTemplate.Sections;
import com.aegis.core.actions.memo.RegulatorMemo.AffectedService;
import com.aegis.core.ai.AiDrafter;
import com.aegis.core.ai.AiDtos;
import com.aegis.core.incidents.VendorIncident;
import com.aegis.core.incidents.VendorIncidentStore;
import com.aegis.core.matching.MatchingDtos.ChangeRef;
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.matching.MatchingDtos.EvidenceChain;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.common.SensitiveText;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
import com.aegis.core.scoring.ScoringService;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignal.SignalType;
import com.aegis.core.signals.ChangeSignalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Regulator-notification memos (E8 story 6): a DORA-shaped notification about one change, drafted only when
 * it materially affects a service. <b>Propose</b> renders the memo from its template — Aegis's facts (the
 * reference, timeline, affected services and components, third-party provider, evidence) around the AI's
 * summary, impact, measures and next steps, or a plain fallback when the AI is unavailable, which never
 * blocks the draft. <b>Approve</b> — a second analyst's, never the drafter's — finalises it for a person to
 * submit through the authority's own channel; Aegis never sends it. <b>Dismiss</b> declines with a reason.
 */
@Service
public class RegulatorMemoService {

    private static final Logger log = LoggerFactory.getLogger(RegulatorMemoService.class);
    private static final int MAX_CHANGE_CONTEXT = 4_000;
    private static final int MAX_SECTION = 1_000;

    private final ScoringService scoring;
    private final ChangeSignalStore signals;
    private final VendorIncidentStore incidents;
    private final AiDrafter drafter;
    private final RegulatorMemoStore store;

    public RegulatorMemoService(ScoringService scoring, ChangeSignalStore signals, VendorIncidentStore incidents,
            AiDrafter drafter, RegulatorMemoStore store) {
        this.scoring = scoring;
        this.signals = signals;
        this.incidents = incidents;
        this.drafter = drafter;
        this.store = store;
    }

    public RegulatorMemo propose(UUID changeSignalId, UUID drafterId) {
        ChangeImpactResponse impact = scoring.scoreChange(changeSignalId);
        SignalType type = impact.change().signalType();
        if (type != SignalType.VULNERABILITY && type != SignalType.VENDOR_OUTAGE) {
            throw new ApiExceptions.BadRequest("A regulator memo is drafted for a vulnerability or a vendor outage");
        }
        if (impact.services().stream().noneMatch(ScoredService::material)) {
            throw new ApiExceptions.Conflict("No service is materially affected by this change, so there is nothing to notify");
        }
        store.findLive(changeSignalId).ifPresent(live -> {
            throw new ApiExceptions.Conflict("A regulator memo for this change is already "
                    + live.status().name().toLowerCase(Locale.ROOT) + " as memo " + live.id());
        });

        ChangeSignal signal = signals.findById(changeSignalId).orElse(null);
        MemoKind kind = type == SignalType.VENDOR_OUTAGE ? MemoKind.MAJOR_INCIDENT : MemoKind.CYBER_THREAT;
        UUID id = UUID.randomUUID();
        String changeTitle = impact.change().title() == null ? impact.change().externalId() : impact.change().title();
        List<ScoredService> scored = impact.services().stream()
                .sorted(Comparator.comparingDouble(ScoredService::score).reversed()).toList();
        List<AffectedService> services = scored.stream()
                .map(service -> new AffectedService(service.service().id(), service.service().name(),
                        service.service().criticality(), service.service().exposure(), service.score(), service.tier(),
                        service.material()))
                .toList();
        Facts facts = new Facts("AEGIS-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT), changeTitle,
                severity(impact.severity(), signal), detectedAt(impact.change(), signal), services, thirdParties(scored),
                components(scored), evidence(impact.change(), signal, scored));
        Drafted drafted = draft(id, kind, type, changeTitle, signal, facts);
        Sections sections = drafted.sections();
        return store.create(new RegulatorMemo(id, changeSignalId, type, changeTitle, kind, facts.reference(),
                facts.detectedAt(), services, sections.summary(), sections.impact(), sections.measures(),
                sections.nextSteps(), drafted.aiDrafted(), drafted.citations(),
                MemoTemplate.render(kind, facts, sections, drafted.citations()), RegulatorMemoStatus.PROPOSED,
                drafterId, null, null, null,
                null, 0));
    }

    /** A second analyst's approval finalises the memo; the drafter never approves their own. */
    public RegulatorMemo approve(UUID memoId, UUID approver) {
        RegulatorMemo memo = get(memoId);
        if (memo.createdBy().equals(approver)) {
            throw new ApiExceptions.Forbidden("You drafted this memo, so someone else has to approve it");
        }
        requireProposed(memo, "approved");
        return store.update(memo.approved(approver, Instant.now()));
    }

    public RegulatorMemo dismiss(UUID memoId, UUID by, String reason) {
        RegulatorMemo memo = get(memoId);
        requireProposed(memo, "dismissed");
        return store.update(memo.dismissed(by, Instant.now(), reason.trim()));
    }

    public RegulatorMemo get(UUID memoId) {
        return store.find(memoId)
                .orElseThrow(() -> new ApiExceptions.NotFound("Regulator memo not found: " + memoId));
    }

    public Page<RegulatorMemo> list(UUID changeSignalId, Collection<RegulatorMemoStatus> statuses, Pageable pageable) {
        return store.findPage(changeSignalId, statuses, pageable);
    }

    private static void requireProposed(RegulatorMemo memo, String decision) {
        if (memo.status() != RegulatorMemoStatus.PROPOSED) {
            throw new ApiExceptions.Conflict("A regulator memo that is "
                    + memo.status().name().toLowerCase(Locale.ROOT) + " cannot be " + decision);
        }
    }

    private record Drafted(Sections sections, boolean aiDrafted, List<String> citations) {
    }

    private Drafted draft(UUID memoId, MemoKind kind, SignalType type, String changeTitle, ChangeSignal signal,
            Facts facts) {
        try {
            AiDtos.Draft draft = drafter.draft(new AiDtos.DraftRequest(memoId.toString(), "memo",
                    context(kind, type, changeTitle, signal, facts)));
            AiDtos.MemoSections memo = draft.memo();
            if (memo == null || blank(memo.summary()) || blank(memo.impact()) || blank(memo.measures())
                    || blank(memo.nextSteps())) {
                throw new IllegalStateException("the AI service returned no memo sections");
            }
            return new Drafted(new Sections(memo.summary().strip(), memo.impact().strip(), memo.measures().strip(),
                    memo.nextSteps().strip()), true, draft.citations() == null ? List.of() : List.copyOf(draft.citations()));
        } catch (RuntimeException unavailable) {
            // The facts stand on their own: an AI outage must never block a notification.
            log.warn("regulator memo {}: no AI draft ({})", memoId, unavailable.getClass().getSimpleName());
            return new Drafted(fallback(type, changeTitle, signal, facts), false, List.of());
        }
    }

    /** What the memo says when the AI could not draft it: only facts Aegis holds, and what must be confirmed. */
    private static Sections fallback(SignalType type, String changeTitle, ChangeSignal signal, Facts facts) {
        String description = signal == null || blank(signal.summary()) ? "No description was published." : signal.summary();
        String summary = cut((type == SignalType.VENDOR_OUTAGE ? "Vendor outage " : "Vulnerability ") + changeTitle
                + (facts.thirdParties().isEmpty() ? "" : " at " + String.join(", ", facts.thirdParties())) + ": "
                + description.strip(), MAX_SECTION);
        List<String> material = facts.services().stream().filter(AffectedService::material)
                .map(service -> service.name() + " (" + service.tier() + ")").toList();
        String impact = cut("Aegis assessed " + material.size() + (material.size() == 1 ? " service" : " services")
                + " as materially affected: " + String.join(", ", material) + ". Clients, transactions and duration "
                + "affected: " + MemoTemplate.TO_BE_CONFIRMED + ".", MAX_SECTION);
        return new Sections(summary, impact, MemoTemplate.TO_BE_CONFIRMED,
                "Confirm the classification, the clients and transactions affected, and the duration before the next report.");
    }

    /** The ai-service's grounding: the change's own text and the deterministic facts, nothing else. */
    private static String context(MemoKind kind, SignalType type, String changeTitle, ChangeSignal signal, Facts facts) {
        String description = signal == null || blank(signal.summary()) ? "(no description published)"
                : cut(signal.summary().strip(), MAX_CHANGE_CONTEXT);
        String services = facts.services().stream()
                .map(service -> String.format(Locale.ROOT, "- %s: %s criticality, %s, impact score %.1f (%s%s)",
                        service.name(), service.criticality(), service.exposure(), service.score(), service.tier(),
                        service.material() ? ", material" : ""))
                .collect(Collectors.joining("\n"));
        return """
                %s: %s %s%s, detected %s: %s
                Severity: %s
                Affected services, as Aegis scored them:
                %s
                %s
                Evidence:
                %s
                """.formatted(kind == MemoKind.MAJOR_INCIDENT ? "ICT-related incident" : "Cyber threat",
                type == SignalType.VENDOR_OUTAGE ? "vendor outage" : "vulnerability", changeTitle,
                facts.thirdParties().isEmpty() ? "" : " at " + String.join(", ", facts.thirdParties()),
                facts.detectedAt() == null ? "at an unknown time" : facts.detectedAt().toString(), description,
                facts.severity() == null ? "not published" : facts.severity(), services,
                facts.components().isEmpty() ? "" : "Affected components:\n" + facts.components().stream()
                        .map(component -> "- " + component).collect(Collectors.joining("\n")),
                facts.evidence().stream().map(line -> "- " + line).collect(Collectors.joining("\n")));
    }

    /** When the entity became aware: an outage's start, else when the change was published or first seen. */
    private Instant detectedAt(ChangeRef change, ChangeSignal signal) {
        if (change.signalType() == SignalType.VENDOR_OUTAGE) {
            Instant started = incidents.findByExternalId(change.sourceId(), change.externalId())
                    .map(VendorIncident::startedAt).orElse(null);
            if (started != null) {
                return started;
            }
        }
        return signal == null ? null : signal.occurredAt();
    }

    /** The severity as published: the CVSS score scoring used, with the feed's own rating when it gave one. */
    private static String severity(SeverityInput severity, ChangeSignal signal) {
        String label = severity != null && !blank(severity.label()) ? severity.label()
                : signal == null || blank(signal.severity()) ? null : signal.severity().strip();
        if (severity != null && severity.cvssBaseScore() != null) {
            return "CVSS " + (severity.cvssVersion() == null ? "" : severity.cvssVersion() + " ") + "base score "
                    + severity.cvssBaseScore().stripTrailingZeros().toPlainString()
                    + (label == null ? "" : " (" + label + ")");
        }
        return label;
    }

    private static List<String> thirdParties(List<ScoredService> services) {
        Set<String> vendors = new LinkedHashSet<>();
        services.forEach(service -> service.evidence().stream().map(EvidenceChain::vendor).filter(Objects::nonNull)
                .forEach(vendor -> vendors.add(vendor.name())));
        return List.copyOf(vendors);
    }

    /** Each affected component once, with the services it reaches. */
    private static List<String> components(List<ScoredService> services) {
        Set<String> lines = new LinkedHashSet<>();
        for (ScoredService service : services) {
            for (EvidenceChain chain : service.evidence()) {
                if (chain.componentPath() != null && !chain.componentPath().isEmpty()) {
                    ComponentRef component = chain.componentPath().getFirst();
                    lines.add(component.name() + " " + component.version() + " (" + component.ecosystem() + ")");
                }
            }
        }
        return List.copyOf(lines);
    }

    private static List<String> evidence(ChangeRef change, ChangeSignal signal, List<ScoredService> services) {
        List<String> lines = new ArrayList<>(DraftEvidence.source(change, signal));
        for (ScoredService service : services) {
            DraftEvidence.service(service).forEach(line -> lines.add(service.service().name() + ": " + line));
        }
        return List.copyOf(lines);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static String cut(String text, int max) {
        text = SensitiveText.redact(text);
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
