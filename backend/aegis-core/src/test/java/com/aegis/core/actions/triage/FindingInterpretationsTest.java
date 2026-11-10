package com.aegis.core.actions.triage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.aegis.core.actions.assess.ReachabilityAssessment;
import com.aegis.core.actions.assess.ReachabilityAssessment.Verdict;
import com.aegis.core.actions.assess.ReachabilityAssessmentStore;
import com.aegis.core.actions.ticket.ResponseStage;
import com.aegis.core.actions.triage.TriageQueue.Item;
import com.aegis.core.actions.triage.TriageQueue.Kind;
import com.aegis.core.actions.triage.TriageQueue.Outage;
import com.aegis.core.actions.triage.TriageQueue.Queue;
import com.aegis.core.actions.upgrade.UpgradeRecommendations.Recommendation;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.ai.AiDtos;
import com.aegis.core.inventory.CodeUsage;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.Reachability;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.matching.MatchingDtos.VulnerabilityRef;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignalStore;
import com.aegis.core.support.FakeAiAssessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The AI reads each open material finding once, worst first, and only again when what it was told has changed. */
class FindingInterpretationsTest {

    private static final AiDtos.Interpretation ANSWER = new AiDtos.Interpretation("LIKELY_REAL", "MEDIUM",
            "A crafted input can run code.", "The service is internet-facing.", "A build-time compromise.", "VERIFY",
            "Check the build.", "Builds compile untrusted input.", "UNKNOWN", List.of("Builds compile only our code."),
            List.of("The advisory's own text."));

    private static final String REVISION = "commit-read";

    private final TriageQueue queue = mock(TriageQueue.class);
    private final ChangeSignalStore signals = mock(ChangeSignalStore.class);
    private final AdvisoryStore advisories = mock(AdvisoryStore.class);
    private final FakeAiAssessor ai = new FakeAiAssessor();
    private final InMemoryStore store = new InMemoryStore();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-09T00:00:00Z"));
    private final ReachabilityAssessmentStore assessments = mock(ReachabilityAssessmentStore.class);
    private final InventoryStore inventory = mock(InventoryStore.class);
    private final FindingInterpretations interpretations = new FindingInterpretations(queue, ai, store, signals,
            advisories, assessments, inventory, new Clock() {
                @Override public java.time.ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override public Instant instant() {
                    return now.get();
                }
            });

    @BeforeEach
    void answers() {
        ai.interprets(ANSWER);
        when(signals.findById(any())).thenAnswer(call -> Optional.of(new ChangeSignal(call.getArgument(0),
                ChangeSignal.SignalType.VULNERABILITY, "osv", "CVE-1", "Babel flaw", "Arbitrary code execution", "HIGH",
                null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH)));
        BusinessService scanned = mock(BusinessService.class);
        when(scanned.getImportScanRevision()).thenReturn(REVISION);
        when(inventory.findBusinessServicesByIds(any())).thenReturn(Map.of(service().id(), scanned));
    }

    @Test
    void readsTheWorstFindingsFirstUpToTheLimit() {
        Item worst = vulnerability("worst", ImpactTier.CRITICAL, ResponseStage.NEW);
        Item next = vulnerability("next", ImpactTier.HIGH, ResponseStage.NEW);
        Item last = vulnerability("last", ImpactTier.HIGH, ResponseStage.NEW);
        queued(worst, next, last);

        FindingInterpretations.Run run = interpretations.interpretNext(2);

        assertThat(run.interpreted()).isEqualTo(2);
        assertThat(run.stopped()).isFalse();
        assertThat(store.saved.keySet()).containsExactly(worst.key(), next.key());
    }

    @Test
    void sendsTheFactsAndNeverTheServiceNameOrOwners() {
        queued(vulnerability("lib", ImpactTier.HIGH, ResponseStage.NEW));

        interpretations.interpretNext(1);

        AiDtos.InterpretationRequest sent = ai.interpretations().getFirst();
        assertThat(sent.kind()).isEqualTo("VULNERABILITY");
        assertThat(sent.changeText()).contains("Babel flaw").contains("Arbitrary code execution");
        assertThat(sent.pkg()).isEqualTo(new AiDtos.PackageRef("npm", "lib"));
        assertThat(sent.installedVersion()).isEqualTo("1.0.0");
        assertThat(sent.fixedVersion()).isEqualTo("2.0.0");
        assertThat(sent.dependencyPath()).containsExactly("parent 3.0.0");
        assertThat(sent.importStatus()).isEqualTo("UNKNOWN");
        assertThat(sent.severity()).isEqualTo("CRITICAL");
        assertThat(sent.exposure()).isEqualTo("INTERNET_FACING");
        assertThat(sent.criticality()).isEqualTo("HIGH");
        assertThat(sent.tier()).isEqualTo("HIGH");
        assertThat(sent.toString()).doesNotContain("Health Care App");
    }

    @Test
    void anOutageIsReadFromTheVendorsOwnWords() {
        Item outage = outage();
        queued(outage);

        interpretations.interpretNext(1);

        AiDtos.InterpretationRequest sent = ai.interpretations().getFirst();
        assertThat(sent.kind()).isEqualTo("VENDOR_OUTAGE");
        assertThat(sent.changeText()).contains("API outage").contains("investigating").contains("major");
        assertThat(sent.vendor()).isEqualTo("Cloud");
        assertThat(sent.pkg()).isNull();
        assertThat(sent.importStatus()).isNull();
        assertThat(store.saved.get(outage.key()).subject()).isEqualTo("Cloud");
    }

    @Test
    void keepsTheAnswerWithWhatItWasAbout() {
        Item item = vulnerability("lib", ImpactTier.HIGH, ResponseStage.NEW);
        queued(item);

        interpretations.interpretNext(1);

        FindingInterpretation kept = store.saved.get(item.key());
        assertThat(kept.serviceId()).isEqualTo(item.service().id());
        assertThat(kept.changeSignalId()).isEqualTo(item.changeSignalId());
        assertThat(kept.subject()).isEqualTo("lib");
        assertThat(kept.signal()).isEqualTo(FindingInterpretation.Signal.LIKELY_REAL);
        assertThat(kept.confidence()).isEqualTo(FindingInterpretation.Confidence.MEDIUM);
        assertThat(kept.suggestedAction()).isEqualTo(FindingInterpretation.SuggestedAction.VERIFY);
        assertThat(kept.condition()).isEqualTo("Builds compile untrusted input.");
        assertThat(kept.conditionMet()).isEqualTo(FindingInterpretation.ConditionMet.UNKNOWN);
        assertThat(kept.assumptions()).containsExactly("Builds compile only our code.");
        assertThat(kept.citations()).containsExactly("The advisory's own text.");
        assertThat(kept.interpretedAt()).isEqualTo(now.get());
    }

    @Test
    void redactsWhatTheAiSaysBeforeKeepingIt() {
        ai.interprets(new AiDtos.Interpretation("UNCERTAIN", "LOW", "password=hunter2", "x", "y", "VERIFY", "z",
                "password=hunter2", "UNKNOWN", List.of(), List.of()));
        Item item = vulnerability("lib", ImpactTier.HIGH, ResponseStage.NEW);
        queued(item);

        interpretations.interpretNext(1);

        assertThat(store.saved.get(item.key()).whatChanged()).doesNotContain("hunter2");
        assertThat(store.saved.get(item.key()).condition()).doesNotContain("hunter2");
    }

    @Test
    void doesNotAskAgainWhileTheFactsAreTheSame() {
        queued(vulnerability("lib", ImpactTier.HIGH, ResponseStage.NEW));
        interpretations.interpretNext(5);

        FindingInterpretations.Run again = interpretations.interpretNext(5);

        assertThat(again.interpreted()).isZero();
        assertThat(ai.interpretations()).hasSize(1);
    }

    @Test
    void asksAgainWhenTheFactsChange() {
        Item before = vulnerability("lib", ImpactTier.HIGH, ResponseStage.NEW);
        queued(before);
        interpretations.interpretNext(5);
        Item after = withTier(before, ImpactTier.CRITICAL);
        queued(after);

        FindingInterpretations.Run again = interpretations.interpretNext(5);

        assertThat(again.interpreted()).isEqualTo(1);
        assertThat(ai.interpretations()).hasSize(2);
    }

    @Test
    void tellsTheAiWhatACodeAssessmentAlreadyConcluded() {
        queued(vulnerability("lib", ImpactTier.HIGH, ResponseStage.NEW));
        assessed(assessment("lib", "CVE-1", REVISION, Verdict.CANT_TELL, "No proxy seen."));

        interpretations.interpretNext(1);

        assertThat(ai.interpretations().getFirst().codeAssessment())
                .isEqualTo(new AiDtos.CodeAssessmentFact("CANT_TELL", "No proxy seen."));
    }

    @Test
    void sendsNoCodeAssessmentWhenNobodyHasAsked() {
        queued(vulnerability("lib", ImpactTier.HIGH, ResponseStage.NEW));

        interpretations.interpretNext(1);

        assertThat(ai.interpretations().getFirst().codeAssessment()).isNull();
    }

    @Test
    void ignoresACodeAssessmentOfAnOlderCommit() {
        queued(vulnerability("lib", ImpactTier.HIGH, ResponseStage.NEW));
        assessed(assessment("lib", "CVE-1", "older-commit", Verdict.CALLED, "It was called then."));

        interpretations.interpretNext(1);

        assertThat(ai.interpretations().getFirst().codeAssessment()).isNull();
    }

    @Test
    void ignoresACodeAssessmentOfAnotherPackageOrVulnerability() {
        queued(vulnerability("lib", ImpactTier.HIGH, ResponseStage.NEW));
        assessed(assessment("other", "CVE-1", REVISION, Verdict.CALLED, "Another package."),
                assessment("lib", "CVE-2", REVISION, Verdict.CALLED, "Another vulnerability."));

        interpretations.interpretNext(1);

        assertThat(ai.interpretations().getFirst().codeAssessment()).isNull();
    }

    @Test
    void keepsAnOverlongCodeAssessmentWithinWhatTheAiServiceAccepts() {
        queued(vulnerability("lib", ImpactTier.HIGH, ResponseStage.NEW));
        assessed(assessment("lib", "CVE-1", REVISION, Verdict.CANT_TELL, "x".repeat(4000)));

        interpretations.interpretNext(1);

        assertThat(ai.interpretations().getFirst().codeAssessment().rationale()).hasSize(1200);
    }

    @Test
    void readsAgainWhenACodeAssessmentArrives() {
        queued(vulnerability("lib", ImpactTier.HIGH, ResponseStage.NEW));
        interpretations.interpretNext(5);
        assessed(assessment("lib", "CVE-1", REVISION, Verdict.CANT_TELL, "No proxy seen."));

        assertThat(interpretations.interpretNext(5).interpreted()).isEqualTo(1);
        assertThat(ai.interpretations()).hasSize(2);
    }

    @Test
    void doesNotReadAgainWhenAskingAgainGivesTheSameVerdict() {
        queued(vulnerability("lib", ImpactTier.HIGH, ResponseStage.NEW));
        assessed(assessment("lib", "CVE-1", REVISION, Verdict.CANT_TELL, "No proxy seen."));
        interpretations.interpretNext(5);
        assessed(assessment("lib", "CVE-1", REVISION, Verdict.CANT_TELL, "Worded differently this time."));

        assertThat(interpretations.interpretNext(5).interpreted()).isZero();
    }

    @Test
    void leavesFindingsNobodyHasToActOnAlone() {
        queued(vulnerability("closed", ImpactTier.HIGH, ResponseStage.CLOSED));

        assertThat(interpretations.interpretNext(5).interpreted()).isZero();
        assertThat(ai.interpretations()).isEmpty();
    }

    @Test
    void aFailureEndsTheRunAndThatFindingWaitsSoTheNextCanBeRead() {
        Item first = vulnerability("first", ImpactTier.CRITICAL, ResponseStage.NEW);
        Item second = vulnerability("second", ImpactTier.HIGH, ResponseStage.NEW);
        queued(first, second);
        ai.fails(new ApiExceptions.UpstreamUnavailable("The AI service could not complete the request (HTTP 502)"));

        FindingInterpretations.Run failed = interpretations.interpretNext(5);

        assertThat(failed.interpreted()).isZero();
        assertThat(failed.stopped()).isTrue();
        assertThat(ai.interpretations()).hasSize(1);

        ai.interprets(ANSWER);
        assertThat(interpretations.interpretNext(1).interpreted()).isEqualTo(1);
        assertThat(store.saved.keySet()).containsExactly(second.key());

        now.set(now.get().plus(FindingInterpretations.RETRY_AFTER).plus(Duration.ofSeconds(1)));
        assertThat(interpretations.interpretNext(1).interpreted()).isEqualTo(1);
        assertThat(store.saved.keySet()).containsExactlyInAnyOrder(first.key(), second.key());
    }

    @Test
    void aChangeNoLongerOnRecordIsSkippedNotFatal() {
        Item gone = vulnerability("gone", ImpactTier.CRITICAL, ResponseStage.NEW);
        Item here = vulnerability("here", ImpactTier.HIGH, ResponseStage.NEW);
        queued(gone, here);
        when(signals.findById(gone.changeSignalId())).thenReturn(Optional.empty());

        FindingInterpretations.Run run = interpretations.interpretNext(5);

        assertThat(run.interpreted()).isEqualTo(1);
        assertThat(store.saved.keySet()).containsExactly(here.key());
    }

    private void assessed(ReachabilityAssessment... kept) {
        when(assessments.findForServices(any())).thenReturn(List.of(kept));
    }

    private static ReachabilityAssessment assessment(String name, String vulnerability, String revision, Verdict verdict,
            String rationale) {
        return new ReachabilityAssessment(UUID.randomUUID(), service().id(), "npm", name, vulnerability, revision, verdict,
                rationale, List.of(), List.of(), List.of(), null, Instant.EPOCH);
    }

    private void queued(Item... items) {
        when(queue.overview()).thenReturn(new Queue(new PageImpl<>(List.of(items)), List.of(), List.of(), List.of()));
    }

    private static ServiceRef service() {
        return new ServiceRef(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Health Care App",
                BusinessCriticality.HIGH, ServiceExposure.INTERNET_FACING);
    }

    private static Item vulnerability(String name, ImpactTier tier, ResponseStage stage) {
        UUID signal = UUID.nameUUIDFromBytes(name.getBytes());
        ComponentRef component = new ComponentRef(UUID.randomUUID(), "npm", name, "1.0.0");
        ComponentRef via = new ComponentRef(UUID.randomUUID(), "npm", "parent", "3.0.0");
        Recommendation upgrade = new Recommendation(service().id(), service().name(), component, via, List.of(),
                MatchConfidence.CONFIRMED, List.of(new VulnerabilityRef("CVE-" + name, signal,
                        MatchConfidence.CONFIRMED, "3.1", null, "CRITICAL")), 76, tier, true, "2.0.0", signal, null,
                null, List.of(), null, null, new Reachability.Verdict(CodeUsage.Status.UNKNOWN, "why", "parent", null),
                null, null);
        return new Item(Kind.VULNERABILITY, signal, service(), List.of(), 76, tier, MatchConfidence.CONFIRMED, upgrade,
                null, null, stage, false, null);
    }

    private static Item withTier(Item item, ImpactTier tier) {
        return new Item(item.kind(), item.changeSignalId(), item.service(), item.owners(), item.score(), tier,
                item.confidence(), item.upgrade(), item.outage(), item.response(), item.stage(), item.canDraft(), null);
    }

    private static Item outage() {
        UUID id = UUID.randomUUID();
        Outage outage = new Outage(id, "API outage", "Cloud", "investigating", "major", Instant.EPOCH, Instant.EPOCH, null);
        return new Item(Kind.VENDOR_OUTAGE, id, service(), List.of(), 60, ImpactTier.HIGH, MatchConfidence.CONFIRMED, null,
                outage, null, ResponseStage.NEW, false, null);
    }

    private static final class InMemoryStore implements FindingInterpretationStore {
        private final Map<String, FindingInterpretation> saved = new LinkedHashMap<>();

        @Override
        public List<FindingInterpretation> findForServices(Collection<UUID> serviceIds) {
            return new ArrayList<>(saved.values());
        }

        @Override
        public List<FindingInterpretation> findForChange(UUID changeSignalId) {
            return saved.values().stream().filter(kept -> kept.changeSignalId().equals(changeSignalId)).toList();
        }

        @Override
        public FindingInterpretation save(FindingInterpretation interpretation) {
            saved.put(interpretation.itemKey(), interpretation);
            return interpretation;
        }
    }
}
