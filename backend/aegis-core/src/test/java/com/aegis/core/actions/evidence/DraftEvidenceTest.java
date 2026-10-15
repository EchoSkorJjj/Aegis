package com.aegis.core.actions.evidence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.inventory.CodeUsage;
import com.aegis.core.inventory.Reachability;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.ChangeRef;
import com.aegis.core.matching.MatchingDtos.EvidenceChain;
import com.aegis.core.matching.MatchingDtos.EvidenceKind;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.scoring.ScoringDtos.ScoreFactor;
import com.aegis.core.scoring.ScoringDtos.ScoreFactorKind;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignal.SignalType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DraftEvidenceTest {

    private static final UUID SIGNAL = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant PUBLISHED = Instant.parse("2026-09-26T08:30:00Z");
    private static final ChangeRef CHANGE = new ChangeRef(SIGNAL, SignalType.VULNERABILITY, "osv", "GHSA-1", "CVE-1");

    @Test
    void theCanonicalVulnerabilityAndItsActualFeedReferenceAreBothAttached() {
        List<String> source = DraftEvidence.source(CHANGE, new ChangeSignal(SIGNAL, SignalType.VULNERABILITY,
                "osv", "GHSA-1", "CVE-1", "Published advisory text.", "HIGH", null, PUBLISHED, PUBLISHED, PUBLISHED));

        assertThat(source).contains("Change: VULNERABILITY CVE-1 (" + SIGNAL + ").", "Source: osv / GHSA-1.",
                "Source time: 2026-09-26T08:30:00Z", "Source excerpt: Published advisory text.",
                "Source URL: not recorded");
    }

    @Test
    void missingSourceMetadataIsExplicitAndTheKnownReferenceStillSurvives() {
        assertThat(DraftEvidence.source(CHANGE, null)).contains("Source: osv / GHSA-1.",
                "Source time: not published", "Source excerpt: not published");
        assertThat(DraftEvidence.source(CHANGE, "Outage details.", PUBLISHED, "https://status.example/incidents/1"))
                .contains("Source URL: https://status.example/incidents/1");
        assertThat(DraftEvidence.source(CHANGE, "x".repeat(4_001), PUBLISHED, null).getLast())
                .endsWith("… [excerpt truncated]");
    }

    @Test
    void evidenceIncludesEveryMatchPathAndTheInputsExplainingTheScore() {
        List<String> evidence = DraftEvidence.service(scored(true));

        assertThat(evidence).contains("Direct dependency is affected.", "A second dependency path is affected.",
                "Affected service: Payments (" + SERVICE + "), HIGH criticality, INTERNAL.",
                "Service owners: 1 assigned (personal details omitted).", "Match confidence: POSSIBLE.",
                "Score calculation: severity 0.9 x exposure 0.9 x reachability 1 x criticality 0.8 x confidence 1"
                        + " x 100 = 64.8"
                        + " (rounded to one decimal).",
                "Score factor CRITICALITY: HIGH; weight 0.8; limiting factor; setting aegis.scoring.criticality.high.");
        assertThat(evidence).anySatisfy(line -> assertThat(line).startsWith("Impact score 64.8 (MEDIUM, material)."));
        assertThat(String.join("\n", evidence)).doesNotContain("Alice", USER.toString());
    }

    @Test
    void evidenceSaysWhetherTheCodeReachesThePackageButNeverWhere() {
        ScoredService base = scored(true);
        Reachability.Verdict imported = new Reachability.Verdict(CodeUsage.Status.IMPORTED, null, null,
                new CodeUsage.Usage(CodeUsage.Status.IMPORTED, null, List.of("axios"), 3, List.of(new CodeUsage.Site(
                        "src/billing/charge.ts", 4, "https://github.com/acme/payments/blob/abc/src/billing/charge.ts#L4"))));
        Reachability.Verdict notImported = new Reachability.Verdict(CodeUsage.Status.NOT_IMPORTED,
                "Nothing in the code Aegis read imports axios", null, null);

        List<String> reached = DraftEvidence.service(withReachability(base, imported));

        assertThat(reached).contains("Code reachability: IMPORTED. The service's code imports it in 3 places.");
        // The files and lines stay in Aegis: a draft goes to the AI and to the issue tracker.
        assertThat(String.join("\n", reached)).doesNotContain("charge.ts", "github.com/acme");
        assertThat(DraftEvidence.service(withReachability(base, notImported)))
                .contains("Code reachability: NOT_IMPORTED. Nothing in the code Aegis read imports axios.");
    }

    private static ScoredService withReachability(ScoredService base, Reachability.Verdict reachability) {
        return new ScoredService(base.service(), base.score(), base.tier(), base.material(), base.confidence(),
                base.breakdown(), base.factors(), reachability, base.explanation(), base.owners(), base.evidence());
    }

    @Test
    void knownOwnerNamesAreRemovedFromBothMatchKindsWithoutLosingTheReasoning() {
        ScoredService base = scored(true);
        List<OwnerRef> owners = List.of(new OwnerRef(UUID.randomUUID(), USER, "Alice"),
                new OwnerRef(UUID.randomUUID(), UUID.randomUUID(), "Alice Smith"),
                new OwnerRef(UUID.randomUUID(), UUID.randomUUID(), "D. [Ops]"),
                new OwnerRef(UUID.randomUUID(), UUID.randomUUID(), "Ann"),
                new OwnerRef(UUID.randomUUID(), UUID.randomUUID(), "李明"));
        ScoredService withOwners = new ScoredService(base.service(), base.score(), base.tier(), base.material(),
                base.confidence(), base.breakdown(), base.factors(), base.reachability(), base.explanation(), owners,
                List.of(new EvidenceChain(EvidenceKind.ADVISORY_COMPONENT, MatchConfidence.POSSIBLE,
                                "CVE-1 affects Annotation 1.0; dependency path Annotation reaches service Payments; owners: "
                                        + "Alice Smith, Alice, Ann (possible match — version is unknown, review before acting)",
                                null, List.of(), null),
                        new EvidenceChain(EvidenceKind.VENDOR_DEPENDENCY, MatchConfidence.CONFIRMED,
                                "Outage affects vendor Cloud; service Payments depends on that vendor; owners: D. [Ops], 李明",
                                null, List.of(), null)));

        String evidence = String.join("\n", DraftEvidence.service(withOwners));

        assertThat(evidence).contains("CVE-1 affects Annotation 1.0; dependency path Annotation reaches service Payments",
                "owners: [REDACTED], [REDACTED], [REDACTED] (possible match — version is unknown, review before acting)",
                "Outage affects vendor Cloud; service Payments depends on that vendor; owners: [REDACTED], [REDACTED]",
                "Service owners: 5 assigned (personal details omitted).", "Impact score 64.8", "Score calculation:")
                .doesNotContain("Alice", "D. [Ops]", "李明", USER.toString());
    }

    @Test
    void sourceSecretsAreRedactedBeforeTheExcerptCanSplitThem() {
        String secret = "ghp_" + "a".repeat(36);
        String summary = "x".repeat(3_990) + " " + secret + " alice@example.test";
        String evidence = String.join("\n", DraftEvidence.source(CHANGE, summary, PUBLISHED,
                "https://user:credential@status.example/incidents/1"));
        assertThat(evidence).contains("[REDACTED]", "[excerpt truncated]", "Source: osv / GHSA-1.")
                .doesNotContain("ghp_", "alice@example.test", "credential");
    }

    @Test
    void outageEvidenceExplainsWhyAnInternalServiceGetsNoExposureDiscount() {
        assertThat(DraftEvidence.service(scored(false)))
                .anySatisfy(line -> assertThat(line).contains("x exposure 1", "Exposure discount does not apply"));
    }

    private static ScoredService scored(boolean exposureApplies) {
        return new ScoredService(new ServiceRef(SERVICE, "Payments", BusinessCriticality.HIGH, ServiceExposure.INTERNAL),
                exposureApplies ? 64.8 : 72.0, ImpactTier.MEDIUM, true, MatchConfidence.POSSIBLE,
                new ScoreBreakdown(0.9, ServiceExposure.INTERNAL, exposureApplies ? 0.9 : 1,
                        exposureApplies, exposureApplies ? CodeUsage.Status.UNKNOWN : null, 1, exposureApplies,
                        BusinessCriticality.HIGH, 0.8, MatchConfidence.POSSIBLE, 1),
                List.of(new ScoreFactor(ScoreFactorKind.CRITICALITY, "HIGH", 0.8, 16.2, true,
                        "aegis.scoring.criticality.high")),
                null,
                "Aegis found an affected dependency in this service.", List.of(new OwnerRef(UUID.randomUUID(), USER, "Alice")),
                List.of(new EvidenceChain(EvidenceKind.ADVISORY_COMPONENT, MatchConfidence.POSSIBLE,
                                "Direct dependency is affected.", null, List.of(), null),
                        new EvidenceChain(EvidenceKind.ADVISORY_COMPONENT, MatchConfidence.CONFIRMED,
                                "A second dependency path is affected.", null, List.of(), null)));
    }
}
