package com.aegis.core.actions.upgrade;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.aegis.core.actions.upgrade.UpgradePullRequestText.Facts;
import com.aegis.core.actions.upgrade.UpgradePullRequestText.Rationale;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpgradePullRequestTextTest {

    private static final UUID PROPOSAL = UUID.fromString("1a2b3c4d-0000-0000-0000-000000000000");

    @Test
    void theTitleNamesTheChangeAndStaysWithinGithubsLimit() {
        assertThat(UpgradePullRequestText.title("org.apache.logging.log4j:log4j-core", "2.14.1", "2.17.1", "CVE-2021-44228"))
                .isEqualTo("Upgrade org.apache.logging.log4j:log4j-core from 2.14.1 to 2.17.1 (CVE-2021-44228)");
        assertThat(UpgradePullRequestText.title("x".repeat(400), "1", "2", "CVE-1\n")).hasSize(256).doesNotContain("\n");
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
                .contains("- [ ] Run `npm install`")
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
                        null, null, "lib`name", "1", "2", "pom.xml", "CONFIRMED", hostile, List.of()),
                Rationale.drafted(hostile, List.of(hostile)));

        assertThat(body)
                .doesNotContain("@security-team", "@alice", "@ops", "#12", "#34", "![x]", "<img")
                .contains("@​security-team", "repo#​12", "!​[x]", "&lt;img")
                .contains("`lib'name`")
                .doesNotContain("Impact score");
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
                        "lib", "1", "2", "pom.xml", "CONFIRMED", null, List.of()),
                Rationale.unavailable("none"));

        assertThat(body).contains("CVE-2099-0019 and 5 more").doesNotContain("CVE-2099-0020");
    }

    private static Facts facts(String confidence, List<String> notCleared) {
        return new Facts(PROPOSAL, "CVE-2021-44228", List.of("CVE-2021-44228", "CVE-2021-45046"), notCleared,
                "Order Service", "HIGH", "INTERNAL", 72.0, "HIGH", "org.apache.logging.log4j:log4j-core", "2.14.1",
                "2.17.1", "pom.xml", confidence, "log4j-core 2.14.1 is in the affected range",
                List.of("Run `npm install` so `package-lock.json` resolves the new version."));
    }
}
