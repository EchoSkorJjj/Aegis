package com.aegis.core.actions.ticket;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.actions.tracker.IssueTracker.Block;
import com.aegis.core.actions.tracker.IssueTracker.IssueDraft;
import com.aegis.core.actions.tracker.IssueTracker.Priority;
import com.aegis.core.actions.upgrade.UpgradeProposal;
import com.aegis.core.actions.upgrade.UpgradeProposal.Edit;
import com.aegis.core.actions.upgrade.UpgradeProposalStatus;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.signals.ChangeSignal.SignalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** What a risk ticket says is Aegis's own, from the impact score: these are its rules. */
class RiskTicketTextTest {

    private static final LocalDate DRAFTED = LocalDate.parse("2026-09-26");

    @ParameterizedTest
    @CsvSource({"CRITICAL,HIGHEST,2026-10-03", "HIGH,HIGH,2026-10-26", "MEDIUM,MEDIUM,2026-12-25",
            "LOW,LOW,2027-03-25"})
    void theTierDecidesThePriorityAndTheDueDate(ImpactTier tier, Priority priority, LocalDate due) {
        assertThat(RiskTicketText.priority(tier)).isEqualTo(priority);
        assertThat(RiskTicketText.dueDate(tier, DRAFTED)).isEqualTo(due);
    }

    @Test
    void aNegligibleRiskHasTheLowestPriorityAndNoDueDate() {
        assertThat(RiskTicketText.priority(ImpactTier.NEGLIGIBLE)).isEqualTo(Priority.LOWEST);
        assertThat(RiskTicketText.dueDate(ImpactTier.NEGLIGIBLE, DRAFTED)).isNull();
    }

    @Test
    void trackerAndCopyableFieldsCannotLeakContactsOrTruncatedSecrets() {
        String secret = "ghp_" + "a".repeat(36);
        assertThat(RiskTicketText.cut("x".repeat(150) + " " + secret, 160)).doesNotContain("ghp_");
        IssueDraft issue = RiskTicketText.issue(ticket(true, List.of("alice@example.test", "password=opaque-credential")),
                "alice@example.test", null);
        assertThat(RiskTicketText.plainText(issue.description())).contains("[REDACTED]")
                .doesNotContain("alice@example.test", "opaque-credential");
        assertThat(RiskTicketText.serviceNow(ticket(false, List.of()), "alice@example.test", null))
                .containsEntry("cmdb_ci", "[REDACTED]");
        assertThat(RiskTicketText.labels(SignalType.VULNERABILITY, "alice@example.test", ImpactTier.HIGH))
                .doesNotContain("alice-example.test");
    }

    @Test
    void labelsAreSafeForJiraAndNameTheVulnerability() {
        assertThat(RiskTicketText.labels(SignalType.VULNERABILITY, "CVE-2021-44228", ImpactTier.HIGH))
                .containsExactly("aegis", "vulnerability", "impact-high", "CVE-2021-44228");
        assertThat(RiskTicketText.labels(SignalType.VULNERABILITY, "GHSA x/y z", ImpactTier.LOW))
                .endsWith("GHSA-x-y-z");
        assertThat(RiskTicketText.labels(SignalType.VENDOR_OUTAGE, "API outage at Acme", ImpactTier.CRITICAL))
                .containsExactly("aegis", "vendor-outage", "impact-critical");
    }

    @Test
    void titlesFitJiraAndSayWhatIsAffected() {
        assertThat(RiskTicketText.title(SignalType.VULNERABILITY, "CVE-2021-44228", "Payments API"))
                .isEqualTo("CVE-2021-44228 affects Payments API");
        assertThat(RiskTicketText.title(SignalType.VENDOR_OUTAGE, "API outage", "Payments API"))
                .isEqualTo("Vendor outage affects Payments API: API outage");
        assertThat(RiskTicketText.title(SignalType.VULNERABILITY, "x".repeat(400), "Payments API"))
                .hasSize(RiskTicketText.MAX_TITLE).endsWith("…");
    }

    @Test
    void theIssueQuotesTheDraftAsPlainBlocksAndSaysWhoDraftedIt() {
        IssueDraft issue = RiskTicketText.issue(ticket(true, List.of("A quoted passage."))
                .filing(UUID.randomUUID(), Instant.now()), "Payments API", null);

        assertThat(issue.title()).isEqualTo("CVE-2021-44228 affects Payments API");
        assertThat(issue.priority()).isEqualTo(Priority.HIGHEST);
        assertThat(issue.dueDate()).isEqualTo(LocalDate.parse("2026-10-03"));
        assertThat(issue.description()).extracting(Block::kind).containsExactly(
                Block.Kind.PARAGRAPH, Block.Kind.HEADING, Block.Kind.PARAGRAPH, Block.Kind.HEADING,
                Block.Kind.PARAGRAPH, Block.Kind.HEADING, Block.Kind.BULLETS, Block.Kind.HEADING, Block.Kind.BULLETS,
                Block.Kind.HEADING, Block.Kind.BULLETS, Block.Kind.PARAGRAPH);
        assertThat(issue.description().get(6).items()).containsExactly("Upgrade log4j-core to 2.17.1.");
        assertThat(issue.description().getLast().text()).contains("AI draft a person reviewed")
                .contains("approved there by a second person");

        IssueDraft fallback = RiskTicketText.issue(ticket(false, List.of()), "Payments API", null);
        assertThat(fallback.description()).extracting(Block::text).doesNotContain("Sources the draft quotes");
        assertThat(fallback.description().getLast().text()).doesNotContain("AI draft");
    }

    @Test
    void theIssueClaimsAnApprovalOnlyOnceThereIsOneAndSaysWhoseItWas() {
        RiskTicket drafted = ticket(true, List.of());
        assertThat(RiskTicketText.issue(drafted, "Payments API", null).description().getLast().text())
                .as("the ServiceNow fields can be copied before anyone decides")
                .endsWith("a person reviewed).")
                .doesNotContain("approved there");
        assertThat(RiskTicketText.issue(drafted.filing(drafted.createdBy(), Instant.now()), "Payments API", null)
                        .description().getLast().text())
                .as("a response without a pull request may be filed by whoever asked for it")
                .endsWith("and approved there by the person who requested it.")
                .doesNotContain("second person");
    }

    @Test
    void anEditedIssueKeepsTheAiDraftsSourcesApartFromTheReviewersText() {
        RiskTicket edited = ticket(true, List.of("A quoted passage.")).edited("Reviewed summary.", "Reviewed risk.",
                "Reviewed impact.", List.of("Act."), UUID.randomUUID(), Instant.now());

        assertThat(RiskTicketText.issue(edited, "Payments API", null).description()).extracting(Block::text)
                .contains("Reviewed summary.", "Sources the AI draft quoted, before a person edited it")
                .doesNotContain("Sources the draft quotes");
    }

    @Test
    void theServiceNowShapeMapsTheTierToImpactAndUrgency() {
        Map<String, Object> incident = RiskTicketText.serviceNow(ticket(true, List.of()), "Payments API", null);

        assertThat(incident).containsEntry("impact", "1").containsEntry("urgency", "1")
                .containsEntry("cmdb_ci", "Payments API").containsEntry("due_date", "2026-10-03 00:00:00");
        assertThat((String) incident.get("short_description")).isEqualTo("CVE-2021-44228 affects Payments API");
        assertThat((String) incident.get("description"))
                .startsWith("Payments API runs a vulnerable log4j-core.")
                .contains("\nRecommended actions\n- Upgrade log4j-core to 2.17.1.");
    }

    @Test
    void theIssueSaysWhatItsUpgradePullRequestChanges() {
        IssueDraft issue = RiskTicketText.issue(ticket(true, List.of()), "Payments API",
                pullRequest(UpgradeProposalStatus.PROPOSED));

        assertThat(issue.description()).extracting(Block::text).contains("Upgrade pull request",
                "Aegis opens it as a draft with this ticket's key in its title, upgrading:");
        assertThat(issue.description()).filteredOn(block -> block.kind() == Block.Kind.BULLETS)
                .anySatisfy(block -> assertThat(block.items()).containsExactly(
                        "org.apache.logging.log4j:log4j-core 2.14.1 to 2.17.1 in api/pom.xml",
                        "org.apache.logging.log4j:log4j-core 2.14.1 to 2.17.1 in worker/pom.xml"));
        assertThat(RiskTicketText.issue(ticket(true, List.of()), "Payments API",
                        pullRequest(UpgradeProposalStatus.DISMISSED)).description())
                .as("a pull request left out is not promised").extracting(Block::text)
                .doesNotContain("Upgrade pull request");
    }

    private static UpgradeProposal pullRequest(UpgradeProposalStatus status) {
        return new UpgradeProposal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CVE-2021-44228",
                UUID.randomUUID(), List.of(
                        new Edit(UUID.randomUUID(), "maven", "org.apache.logging.log4j:log4j-core", "2.14.1", "2.17.1",
                                "api/pom.xml"),
                        new Edit(UUID.randomUUID(), "maven", "org.apache.logging.log4j:log4j-core", "2.14.1", "2.17.1",
                                "worker/pom.xml")),
                "https://github.com/acme/payments", null, "aegis/upgrade-log4j-core-2.17.1-1a2b3c4d",
                MatchConfidence.CONFIRMED, 72.0, ImpactTier.HIGH, "Upgrade log4j-core", "body", status,
                UUID.randomUUID(), Instant.now(), null, null, null, null, null, null, 0);
    }

    private static RiskTicket ticket(boolean aiDrafted, List<String> citations) {
        return new RiskTicket(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SignalType.VULNERABILITY,
                "CVE-2021-44228", "CVE-2021-44228 affects Payments API", Priority.HIGHEST,
                LocalDate.parse("2026-10-03"), 92.0, ImpactTier.CRITICAL,
                List.of("aegis", "vulnerability", "impact-critical", "CVE-2021-44228"),
                "Payments API runs a vulnerable log4j-core.", "Remote code execution.", "Takeover of the service.",
                List.of("Upgrade log4j-core to 2.17.1."), List.of("log4j-core 2.14.1 is in the affected range."),
                aiDrafted, citations, RiskTicketStatus.PROPOSED, UUID.randomUUID(), Instant.now(), null, null, null,
                null, null, null, null, null, null, 0);
    }
}
