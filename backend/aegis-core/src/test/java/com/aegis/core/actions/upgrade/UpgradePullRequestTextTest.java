package com.aegis.core.actions.upgrade;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.aegis.core.actions.upgrade.UpgradeProposal.Edit;
import com.aegis.core.actions.upgrade.UpgradePullRequestText.Facts;
import com.aegis.core.actions.upgrade.UpgradePullRequestText.Rationale;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpgradePullRequestTextTest {

    private static final UUID PROPOSAL = UUID.fromString("1a2b3c4d-0000-0000-0000-000000000000");

    @Test
    void secretsAreRemovedBeforeRationaleAndCitationsAreTruncatedOrEscaped() {
        String secret = "ghp_" + "a".repeat(36);
        String body = UpgradePullRequestText.body(facts("CONFIRMED", List.of()),
                Rationale.drafted("x".repeat(7_990) + " " + secret,
                        List.of("x".repeat(490) + " " + secret, "alice@example.test")));
        assertThat(body).contains("[REDACTED]", "### Rationale drafted by AI")
                .doesNotContain("ghp_", "alice@example.test");
        assertThat(UpgradePullRequestText.title("alice@example.test", "1", "2", "CVE-1"))
                .contains("[REDACTED]").doesNotContain("alice@example.test");
    }

    @Test
    void theTitleNamesTheChangeAndStaysWithinGithubsLimit() {
        assertThat(UpgradePullRequestText.title("org.apache.logging.log4j:log4j-core", "2.14.1", "2.17.1", "CVE-2021-44228"))
                .isEqualTo("Upgrade org.apache.logging.log4j:log4j-core from 2.14.1 to 2.17.1 (CVE-2021-44228)");
        assertThat(UpgradePullRequestText.title("x".repeat(400), "1", "2", "CVE-1\n")).hasSize(256).doesNotContain("\n");
    }

    @Test
    void severalEditsAreNamedByPackageAndSharedVersion() {
        Edit backend = edit("axios", "1.5.1", "1.8.2", "backend/package.json");
        Edit frontend = edit("axios", "1.6.0", "1.8.2", "frontend/package.json");
        assertThat(UpgradePullRequestText.title(List.of(backend), "CVE-2024-39338"))
                .isEqualTo("Upgrade axios from 1.5.1 to 1.8.2 (CVE-2024-39338)");
        assertThat(UpgradePullRequestText.title(List.of(backend, frontend), "CVE-2024-39338"))
                .isEqualTo("Upgrade axios to 1.8.2 in 2 manifests (CVE-2024-39338)");
        assertThat(UpgradePullRequestText.title(List.of(backend, edit("follow-redirects", "1.0.0", "1.15.6",
                "backend/package.json")), "CVE-2024-39338"))
                .isEqualTo("Upgrade axios and follow-redirects (CVE-2024-39338)");
    }

    @Test
    void aResponsesPullRequestCarriesItsTicketsKeyOnce() {
        String titled = UpgradePullRequestText.titled("SEC-12", "Upgrade axios to 1.8.2 in 2 manifests (CVE-1)");
        assertThat(titled).isEqualTo("SEC-12: Upgrade axios to 1.8.2 in 2 manifests (CVE-1)");
        assertThat(UpgradePullRequestText.titled("SEC-12", titled)).as("a retry keeps one key").isEqualTo(titled);
        assertThat(UpgradePullRequestText.tracking("SEC-12", "body\n")).endsWith("Tracks risk ticket **SEC-12** in the "
                + "organization's issue tracker.\n");
    }

    @Test
    void aBodyWithSeveralEditsListsEachOne() {
        String body = UpgradePullRequestText.body(new Facts(PROPOSAL, "CVE-2024-39338", List.of("CVE-2024-39338"),
                List.of(), "Payments", "CRITICAL", "INTERNET_FACING", 91.0, "CRITICAL",
                List.of(edit("axios", "1.5.1", "1.8.2", "backend/package.json"),
                        edit("axios", "1.6.0", "1.8.2", "frontend/package.json")),
                "CONFIRMED", List.of(), List.of()), Rationale.unavailable("none"));

        assertThat(body)
                .contains("Remediates **CVE-2024-39338** for the Payments service by upgrading every declaration below.")
                .contains("| `axios` | `backend/package.json` | 1.5.1 | 1.8.2 |")
                .contains("| `axios` | `frontend/package.json` | 1.6.0 | 1.8.2 |")
                .contains("**Why these versions:**")
                .contains("- [ ] The build and tests pass with the upgraded versions.")
                .doesNotContain("Finish it before merging");
    }

    @Test
    void whatIsLeftToDoByHandIsFlaggedAtTheTopAndComesFirstBeforeMerging() {
        List<String> followUps = new NpmPackageJsonEditor().followUps(List.of("client/package.json", "server/package.json"));
        String body = UpgradePullRequestText.body(new Facts(PROPOSAL, "CVE-2023-45857", List.of("CVE-2023-45857"),
                List.of(), "HealthcareApp", "HIGH", "INTERNET_FACING", 80.0, "HIGH",
                List.of(edit("axios", "1.5.0", "1.18.0", "client/package.json"),
                        edit("axios", "1.5.1", "1.18.0", "server/package.json")),
                "CONFIRMED", List.of(), followUps), Rationale.unavailable("none"));

        assertThat(body)
                .containsSubsequence(
                        "**Finish it before merging:** Aegis edits the manifests only, so do the first steps under "
                                + "*Before merging* on this branch.",
                        "| Service |",
                        "### Before merging\n\n- [ ] Refresh the lockfiles in `client/` and `server/` and commit them "
                                + "to this branch",
                        "- [ ] The build and tests pass with the upgraded versions.");
    }

    @Test
    void theBodyStatesTheDeterministicFactsAndLabelsTheAiRationale() {
        String body = UpgradePullRequestText.body(facts("CONFIRMED", List.of()),
                Rationale.drafted("Log4Shell allows remote code execution.\nUpgrade promptly.", List.of("JNDI lookups...")));

        assertThat(body)
                .contains("Upgrades `org.apache.logging.log4j:log4j-core` from **2.14.1** to **2.17.1** in `pom.xml`")
                .contains("| Service | Order Service (high criticality, internal) |")
                .contains("| Impact score | 72.0 (high) |")
                .contains("| Checked against | CVE-2021-44228, CVE-2021-45046 |")
                .contains("### Rationale drafted by AI (unverified)")
                .contains("> Log4Shell allows remote code execution.\n> Upgrade promptly.")
                .contains("> JNDI lookups...")
                .contains("### Before merging\n\n- [ ] Run `npm install`")
                .contains("proposal `" + PROPOSAL + "`")
                .contains("Aegis never merges")
                .doesNotContain("POSSIBLE, not confirmed");
    }

    @Test
    void untrustedTextCannotMentionCrossLinkEmbedOrInjectHtml() {
        String hostile = "Ping @security-team and @alice, see acme/repo#12 and #34, ![x](https://evil.example/p.png) "
                + "<img src=x onerror=alert(1)>";
        String body = UpgradePullRequestText.body(
                new Facts(PROPOSAL, "CVE-2099-1", List.of("CVE-2099-1"), List.of(), "Svc @ops", "HIGH", "INTERNAL",
                        null, null, List.of(edit("lib`name", "1", "2", "pom.xml")), "CONFIRMED", List.of(hostile), List.of()),
                Rationale.drafted(hostile, List.of(hostile)));

        assertThat(body)
                .doesNotContain("@security-team", "@alice", "@ops", "#12", "#34", "![x]", "<img")
                .contains("@​security-team", "repo#​12", "!​[x]", "&lt;img")
                .contains("`lib'name`")
                .doesNotContain("Impact score", "Finish it before merging");
    }

    @Test
    void aPossibleMatchAndUnclearedAdvisoriesAreCalledOut() {
        String body = UpgradePullRequestText.body(facts("POSSIBLE", List.of("CVE-2099-0001")),
                Rationale.unavailable("the AI service could not complete the request"));

        assertThat(body)
                .contains("**Not cleared by this upgrade:** CVE-2099-0001.")
                .contains("**The match is POSSIBLE, not confirmed:**")
                .contains("_No AI rationale: the AI service could not complete the request._");
    }

    @Test
    void longAdvisoryListsAreSummarised() {
        List<String> many = IntStream.range(0, 25).mapToObj(i -> "CVE-2099-%04d".formatted(i)).toList();
        String body = UpgradePullRequestText.body(
                new Facts(PROPOSAL, "CVE-2099-0000", many, List.of(), "Svc", "LOW", "ISOLATED", 1.0, "NEGLIGIBLE",
                        List.of(edit("lib", "1", "2", "pom.xml")), "CONFIRMED", List.of(), List.of()),
                Rationale.unavailable("none"));

        assertThat(body).contains("CVE-2099-0019 and 5 more").doesNotContain("CVE-2099-0020");
    }

    @Test
    void evidenceIsKeptAsSeparateLinesWithoutDroppingLaterPathsOrSourceDetails() {
        String longPath = "Dependency path: " + "x".repeat(2_100);
        String body = UpgradePullRequestText.body(new Facts(PROPOSAL, "CVE-1", List.of("CVE-1"), List.of(),
                "Payments", "HIGH", "INTERNAL", 72.0, "HIGH", List.of(edit("lib", "1", "2", "pom.xml")),
                "CONFIRMED", List.of(longPath, "Source: osv / GHSA-1.", "Score calculation: 0.9 x 1 x 0.8 x 1 x 100 = 72.0."),
                List.of()), Rationale.unavailable("none"));

        assertThat(body).contains("### Evidence at drafting", "- " + longPath, "- Source: osv / GHSA-1.",
                "- Score calculation: 0.9 x 1 x 0.8 x 1 x 100 = 72.0.");
    }

    @Test
    void evidenceFromManyDependencyPathsStaysWithinTheBodyLimitAndKeepsTheScoreAndSource() {
        List<String> evidence = new java.util.ArrayList<>(IntStream.range(0, 400)
                .mapToObj(i -> "CVE-1 affects minimist 1.2.5; dependency path app -> lib%03d -> minimist reaches service "
                        .formatted(i) + "Portal; owners: [REDACTED] " + "x".repeat(120))
                .toList());
        evidence.addAll(List.of("Score calculation: 0.9 x 1 x 0.8 x 1 x 100 = 72.0.", "Source: osv / GHSA-1."));
        String body = UpgradePullRequestText.body(new Facts(PROPOSAL, "CVE-1", List.of("CVE-1"), List.of(),
                "Portal", "HIGH", "INTERNAL", 72.0, "HIGH", List.of(edit("minimist", "1.2.5", "1.2.6", "package.json")),
                "CONFIRMED", evidence, List.of()), Rationale.drafted("r".repeat(8_000), List.of("c".repeat(500))));

        assertThat(body.length()).isLessThan(60_000);
        assertThat(body).contains("lib000 -> minimist", "lib399 -> minimist",
                "- Score calculation: 0.9 x 1 x 0.8 x 1 x 100 = 72.0.", "- Source: osv / GHSA-1.")
                .containsPattern("- … \\d+ more lines of evidence left out").doesNotContain("lib200 -> minimist");
    }

    private static Facts facts(String confidence, List<String> notCleared) {
        return new Facts(PROPOSAL, "CVE-2021-44228", List.of("CVE-2021-44228", "CVE-2021-45046"), notCleared,
                "Order Service", "HIGH", "INTERNAL", 72.0, "HIGH",
                List.of(edit("org.apache.logging.log4j:log4j-core", "2.14.1", "2.17.1", "pom.xml")), confidence,
                List.of("log4j-core 2.14.1 is in the affected range"),
                List.of("Run `npm install` so `package-lock.json` resolves the new version."));
    }

    private static Edit edit(String packageName, String from, String to, String manifest) {
        return new Edit(UUID.randomUUID(), "npm", packageName, from, to, manifest);
    }
}
