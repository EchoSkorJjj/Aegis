package com.aegis.core.actions.triage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.aegis.core.actions.assess.ReachabilityAssessment;
import com.aegis.core.actions.assess.ReachabilityAssessmentStore;
import com.aegis.core.actions.assess.ReachabilityAssessments;
import com.aegis.core.actions.triage.FindingInterpretation.ConditionMet;
import com.aegis.core.actions.triage.FindingInterpretation.Confidence;
import com.aegis.core.actions.triage.FindingInterpretation.Signal;
import com.aegis.core.actions.triage.FindingInterpretation.SuggestedAction;
import com.aegis.core.actions.triage.TriageQueue.Item;
import com.aegis.core.actions.upgrade.UpgradeRecommendations.Recommendation;
import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.ai.AiAssessor;
import com.aegis.core.ai.AiDtos;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.platform.common.SensitiveText;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Has the AI read each open material finding, worst first: what changed, why it matters to this service and what to
 * do. Nobody asks for it, so it works from what Aegis already holds, never code, and tells the AI nothing that names
 * the service or its owners. That includes any code assessment someone asked for at the commit last read, whose verdict
 * limits what the reading may claim, so one arriving means the finding is read again. The answer is a suggestion:
 * scoring and ranking never read it.
 *
 * <p>A failure ends the run, because the likeliest cause is the AI's budget or an outage, and that finding waits
 * {@link #RETRY_AFTER} so one the AI always refuses can't hold up everything ranked below it. The wait is in memory,
 * on the one instance holding the scheduler lease: losing it to a restart costs one retry.
 */
@Service
public class FindingInterpretations {

    private static final Logger log = LoggerFactory.getLogger(FindingInterpretations.class);

    static final Duration RETRY_AFTER = Duration.ofMinutes(15);
    private static final int MAX_CHANGE_CHARS = 2500;
    private static final int MAX_VERSION_CHARS = 64;
    private static final int MAX_VENDOR_CHARS = 200;
    private static final int MAX_LABEL_CHARS = 32;
    private static final int MAX_SUBJECT_CHARS = 255;
    private static final int MAX_RATIONALE_CHARS = 1200;
    /** Moves when what a reading is asked or allowed to say does, so every finding is read again under the new rules. */
    private static final String READING_VERSION = "reading.v2";

    /** How a run went: how many findings the AI read, and whether a failure ended it early. */
    public record Run(int interpreted, boolean stopped) {
    }

    /** A reading as the API shows it. */
    public record View(UUID serviceId, String subject, Signal signal, Confidence confidence, String whatChanged,
                       String whyItMatters, String whatNext, SuggestedAction suggestedAction, String actionDetail,
                       String condition, ConditionMet conditionMet, List<String> assumptions, List<String> citations,
                       Instant interpretedAt) {
    }

    private final TriageQueue queue;
    private final AiAssessor ai;
    private final FindingInterpretationStore store;
    private final ChangeSignalStore signals;
    private final AdvisoryStore advisories;
    private final ReachabilityAssessmentStore assessments;
    private final InventoryStore inventory;
    private final Clock clock;
    private final Map<String, Instant> waiting = new ConcurrentHashMap<>();

    @Autowired
    public FindingInterpretations(TriageQueue queue, AiAssessor ai, FindingInterpretationStore store,
            ChangeSignalStore signals, AdvisoryStore advisories, ReachabilityAssessmentStore assessments,
            InventoryStore inventory) {
        this(queue, ai, store, signals, advisories, assessments, inventory, Clock.systemUTC());
    }

    FindingInterpretations(TriageQueue queue, AiAssessor ai, FindingInterpretationStore store,
            ChangeSignalStore signals, AdvisoryStore advisories, ReachabilityAssessmentStore assessments,
            InventoryStore inventory, Clock clock) {
        this.queue = queue;
        this.ai = ai;
        this.store = store;
        this.signals = signals;
        this.advisories = advisories;
        this.assessments = assessments;
        this.inventory = inventory;
        this.clock = clock;
    }

    /** What the AI has said about this change, per service, for the finding's own page. */
    public List<View> forChange(UUID changeSignalId) {
        return store.findForChange(changeSignalId).stream().map(FindingInterpretations::view).toList();
    }

    /** Read up to {@code limit} open findings the AI hasn't, or whose facts have changed since it did. */
    public Run interpretNext(int limit) {
        Instant now = clock.instant();
        waiting.values().removeIf(until -> !until.isAfter(now));
        List<Item> open = queue.overview().items().getContent().stream().filter(item -> item.stage().isOpen()).toList();
        if (open.isEmpty()) {
            return new Run(0, false);
        }
        Map<String, String> kept = store.findForServices(open.stream().map(item -> item.service().id()).distinct().toList())
                .stream().collect(Collectors.toMap(saved -> rowKey(saved.serviceId(), saved.itemKey()),
                        FindingInterpretation::fingerprint));
        Map<String, ReachabilityAssessment> assessed = currentAssessments(open);
        int interpreted = 0;
        for (Item item : open) {
            if (interpreted == limit) {
                break;
            }
            String rowKey = rowKey(item.service().id(), item.key());
            if (waiting.containsKey(rowKey)) {
                continue;
            }
            AiDtos.InterpretationRequest request = requestFor(item, assessed);
            if (request == null) {
                continue;
            }
            String fingerprint = fingerprint(item.changeSignalId(), request);
            if (fingerprint.equals(kept.get(rowKey))) {
                continue;
            }
            try {
                store.save(interpretation(item, ai.interpret(request), fingerprint, now));
                interpreted++;
            } catch (RuntimeException failure) {
                log.warn("finding interpretation: stopped this run on item {} in service {} ({})", item.changeSignalId(),
                        item.service().id(), failure.toString());
                waiting.put(rowKey, now.plus(RETRY_AFTER));
                return new Run(interpreted, true);
            }
        }
        return new Run(interpreted, false);
    }

    /** The code assessments at the commit each service was last read at: an older commit's no longer describes it. */
    private Map<String, ReachabilityAssessment> currentAssessments(List<Item> open) {
        List<UUID> serviceIds = open.stream().filter(item -> item.upgrade() != null).map(item -> item.service().id())
                .distinct().toList();
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BusinessService> services = inventory.findBusinessServicesByIds(serviceIds);
        return assessments.findForServices(serviceIds).stream()
                .filter(saved -> services.containsKey(saved.serviceId())
                        && saved.revision().equals(services.get(saved.serviceId()).getImportScanRevision()))
                .collect(Collectors.toMap(saved -> assessmentKey(saved.serviceId(), saved.ecosystem(), saved.packageName(),
                        saved.vulnerabilityId()), Function.identity(), (first, second) -> first));
    }

    private static String assessmentKey(UUID serviceId, String ecosystem, String packageName, String vulnerabilityId) {
        return String.join("\0", serviceId.toString(), ecosystem, packageName, vulnerabilityId);
    }

    private AiDtos.InterpretationRequest requestFor(Item item, Map<String, ReachabilityAssessment> assessed) {
        if (item.upgrade() != null) {
            return vulnerability(item, item.upgrade(), assessed);
        }
        return item.outage() == null ? null : outage(item);
    }

    private AiDtos.InterpretationRequest vulnerability(Item item, Recommendation upgrade,
            Map<String, ReachabilityAssessment> assessed) {
        ChangeSignal signal = signals.findById(item.changeSignalId()).orElse(null);
        if (signal == null) {
            return null;
        }
        Advisory advisory = advisories.findByExternalId(signal.sourceId(), signal.externalId()).orElse(null);
        String text = ReachabilityAssessments.advisoryText(signal.title(), advisory == null ? null : advisory.description(),
                advisory == null ? null : advisory.details(), signal.summary());
        if (text.isBlank()) {
            return null;
        }
        ComponentRef component = upgrade.component();
        String vulnerabilityId = advisory != null && advisory.canonicalId() != null ? advisory.canonicalId()
                : signal.externalId();
        ReachabilityAssessment kept = assessed.get(assessmentKey(item.service().id(), component.ecosystem(), component.name(),
                vulnerabilityId));
        AiDtos.CodeAssessmentFact codeAssessment = kept == null ? null
                : new AiDtos.CodeAssessmentFact(kept.verdict().name(), cut(kept.rationale(), MAX_RATIONALE_CHARS));
        String severity = upgrade.vulnerabilities().stream()
                .filter(candidate -> item.changeSignalId().equals(candidate.changeSignalId()))
                .map(candidate -> candidate.severity()).filter(value -> value != null).findFirst().orElse(null);
        List<String> path = upgrade.via() == null ? List.of() : List.of(upgrade.via().name() + " " + upgrade.via().version());
        String imported = upgrade.reachability() == null ? "UNKNOWN" : upgrade.reachability().status().name();
        return new AiDtos.InterpretationRequest(UUID.randomUUID().toString(), item.kind().name(), text,
                new AiDtos.PackageRef(component.ecosystem(), component.name()), cut(component.version(), MAX_VERSION_CHARS),
                cut(upgrade.toVersion(), MAX_VERSION_CHARS), path, imported, null, cut(severity, MAX_LABEL_CHARS),
                codeAssessment, label(item.service().exposure()), label(item.service().criticality()), label(item.tier()));
    }

    private AiDtos.InterpretationRequest outage(Item item) {
        TriageQueue.Outage outage = item.outage();
        String text = cut(SensitiveText.redact(java.util.stream.Stream.of(outage.title(),
                        outage.status() == null ? null : "Status: " + outage.status(),
                        outage.impact() == null ? null : "Impact: " + outage.impact())
                .filter(part -> part != null && !part.isBlank()).collect(Collectors.joining("\n"))), MAX_CHANGE_CHARS);
        if (text.isBlank()) {
            return null;
        }
        return new AiDtos.InterpretationRequest(UUID.randomUUID().toString(), item.kind().name(), text, null, null, null,
                List.of(), null, cut(outage.vendorName(), MAX_VENDOR_CHARS), null, null, label(item.service().exposure()),
                label(item.service().criticality()), label(item.tier()));
    }

    private static FindingInterpretation interpretation(Item item, AiDtos.Interpretation answer, String fingerprint,
            Instant now) {
        String subject = item.upgrade() != null ? item.upgrade().component().name()
                : item.outage().vendorName() == null ? "Vendor" : item.outage().vendorName();
        return new FindingInterpretation(UUID.randomUUID(), item.service().id(), item.key(), item.changeSignalId(),
                cut(subject, MAX_SUBJECT_CHARS), Signal.valueOf(answer.signal()), Confidence.valueOf(answer.confidence()),
                SensitiveText.redact(answer.whatChanged()), SensitiveText.redact(answer.whyItMatters()),
                SensitiveText.redact(answer.whatNext()), SuggestedAction.valueOf(answer.suggestedAction()),
                SensitiveText.redact(answer.actionDetail()), SensitiveText.redact(answer.condition()),
                ConditionMet.valueOf(answer.conditionMet()),
                answer.assumptions().stream().map(SensitiveText::redact).toList(),
                answer.citations().stream().map(SensitiveText::redact).toList(), fingerprint, now);
    }

    private static String rowKey(UUID serviceId, String itemKey) {
        return serviceId + "\0" + itemKey;
    }

    /**
     * A hash of what the AI is told, without the request's random id: the same facts make the same fingerprint. Of a code
     * assessment only the verdict counts, since asking again rewords its reasoning without changing what it allows.
     */
    private static String fingerprint(UUID changeSignalId, AiDtos.InterpretationRequest request) {
        String facts = String.join("\0", READING_VERSION, String.valueOf(changeSignalId), request.kind(),
                request.codeAssessment() == null ? "not assessed" : request.codeAssessment().verdict(), request.changeText(),
                String.valueOf(request.pkg()), String.valueOf(request.installedVersion()),
                String.valueOf(request.fixedVersion()), String.valueOf(request.dependencyPath()),
                String.valueOf(request.importStatus()), String.valueOf(request.vendor()),
                String.valueOf(request.severity()), request.exposure(), request.criticality(), request.tier());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    private static View view(FindingInterpretation saved) {
        return new View(saved.serviceId(), saved.subject(), saved.signal(), saved.confidence(), saved.whatChanged(),
                saved.whyItMatters(), saved.whatNext(), saved.suggestedAction(), saved.actionDetail(), saved.condition(), saved.conditionMet(),
                saved.assumptions(),
                saved.citations(), saved.interpretedAt());
    }

    private static String label(Enum<?> value) {
        return value == null ? "UNKNOWN" : value.name();
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
