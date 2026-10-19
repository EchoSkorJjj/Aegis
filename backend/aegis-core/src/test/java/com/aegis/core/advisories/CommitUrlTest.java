package com.aegis.core.advisories;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fix commit among an advisory's references (E11-5): only a commit on github.com or gitlab.com, rebuilt from its
 * validated parts — the advisory is untrusted, so nothing else of its URL is kept or ever fetched.
 */
class CommitUrlTest {

    private static final String SHA = "cc6756b61ed90b6b9227f670e0408d5d9bd48551";

    @Test
    void aGitHubCommitIsKeptAsItsCanonicalUrlWithItsDiffBesideIt() {
        CommitUrl commit = CommitUrl.parse("https://github.com/FasterXML/jackson-databind/commit/" + SHA.toUpperCase()
                + "?diff=split#top").orElseThrow();

        assertThat(commit.url()).isEqualTo("https://github.com/FasterXML/jackson-databind/commit/" + SHA);
        assertThat(commit.diffUri()).isEqualTo(URI.create(
                "https://github.com/FasterXML/jackson-databind/commit/" + SHA + ".diff"));
    }

    @Test
    void aCommitOfAPullRequestAndAGitLabCommitAreCommitsToo() {
        assertThat(CommitUrl.parse("https://github.com/vercel/next.js/pull/86413/commits/" + SHA))
                .hasValueSatisfying(commit -> assertThat(commit.url())
                        .isEqualTo("https://github.com/vercel/next.js/commit/" + SHA));
        assertThat(CommitUrl.parse("https://gitlab.com/gitlab-org/ruby/gems/gitlab-mail/-/commit/" + SHA))
                .hasValueSatisfying(commit -> {
                    assertThat(commit.url()).isEqualTo("https://gitlab.com/gitlab-org/ruby/gems/gitlab-mail/-/commit/" + SHA);
                    assertThat(commit.diffUri()).hasToString(commit.url() + ".diff");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/FasterXML/jackson-databind/pull/6129",
            "https://github.com/FasterXML/jackson-databind/releases/tag/jackson-databind-2.18.10",
            "https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wjgm-6hv5-3cvf",
            "https://github.com/lodash/lodash/blob/ddfd9b11a0126db2302cb70ec9973b66baec0975/lodash.js#L14851",
            "https://evil.example/FasterXML/jackson-databind/commit/" + SHA,
            "https://github.com.evil.example/o/r/commit/" + SHA,
            "https://user:pass@github.com/o/r/commit/" + SHA,
            "http://github.com:8080/o/r/commit/" + SHA,
            "https://github.com/../r/commit/" + SHA,
            "https://github.com/o/r/commit/not-a-sha",
            "https://github.com/o/r/commit/abc12",
            "file:///etc/passwd",
            ""})
    void anythingElseIsNoCommit(String url) {
        assertThat(CommitUrl.parse(url)).isEmpty();
    }

    @Test
    void anAdvisorysReferencesGiveItsCommitsOnceEachAtMostFive() {
        List<String> references = List.of(
                "https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wjgm-6hv5-3cvf",
                "https://github.com/FasterXML/jackson-databind/commit/" + SHA,
                "https://github.com/FasterXML/jackson-databind/commit/" + SHA + "#diff",
                "https://github.com/o/r/commit/1111111", "https://github.com/o/r/commit/2222222",
                "https://github.com/o/r/commit/3333333", "https://github.com/o/r/commit/4444444",
                "https://github.com/o/r/commit/5555555");

        assertThat(CommitUrl.among(references)).containsExactly(
                "https://github.com/FasterXML/jackson-databind/commit/" + SHA, "https://github.com/o/r/commit/1111111",
                "https://github.com/o/r/commit/2222222", "https://github.com/o/r/commit/3333333",
                "https://github.com/o/r/commit/4444444");
        assertThat(CommitUrl.among(null)).isEmpty();
    }
}
