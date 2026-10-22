package com.aegis.core.actions.assess;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an advisory's fix commits changed (E11-5): fetched as diffs from the commit's own host only, at most three, each
 * once (a commit never changes), and nothing when switched off or when a fetch fails — never a failed assessment.
 */
class FixCommitsTest {

    private static final String SHA = "cc6756b61ed90b6b9227f670e0408d5d9bd48551";
    private static final String DIFF = """
            diff --git a/src/Lib.java b/src/Lib.java
            @@ -1,3 +1,4 @@ public Object load(String text)
            +    check(text);
            """;

    private final List<URI> fetched = new ArrayList<>();
    private final DiffSource source = uri -> {
        fetched.add(uri);
        return uri.toString().contains("broken") ? Optional.empty() : Optional.of(DIFF);
    };

    @Test
    void theFixCommitsAreFetchedAsDiffsAndSummarisedEachOnce() {
        FixCommits fixes = new FixCommits(true, source);
        List<String> commits = List.of("https://github.com/o/r/commit/" + SHA);

        assertThat(fixes.summary(commits)).isEqualTo("src/Lib.java: changed in public Object load(String text)");
        assertThat(fixes.summary(commits)).isEqualTo("src/Lib.java: changed in public Object load(String text)");
        assertThat(fetched).containsExactly(URI.create("https://github.com/o/r/commit/" + SHA + ".diff"));
    }

    @Test
    void atMostThreeCommitsAndOnlyCommitsAreFetchedAndAFailureIsJustLeftOut() {
        FixCommits fixes = new FixCommits(true, source);

        fixes.summary(List.of("https://github.com/o/broken/commit/1111111", "https://github.com/o/r/commit/2222222",
                "https://evil.example/o/r/commit/3333333", "https://github.com/o/r/commit/4444444",
                "https://github.com/o/r/commit/5555555"));

        assertThat(fetched).extracting(URI::toString).containsExactly(
                "https://github.com/o/broken/commit/1111111.diff", "https://github.com/o/r/commit/2222222.diff",
                "https://github.com/o/r/commit/4444444.diff");
    }

    @Test
    void sameChangesFromTwoCommitsAreSaidOnceAndNothingIsFetchedWhenSwitchedOff() {
        FixCommits fixes = new FixCommits(true, source);
        assertThat(fixes.summary(List.of("https://github.com/o/r/commit/1111111", "https://github.com/o/r/commit/2222222")))
                .isEqualTo("src/Lib.java: changed in public Object load(String text)");
        assertThat(fixes.summary(List.of())).isNull();

        FixCommits off = new FixCommits(false, source);
        fetched.clear();
        assertThat(off.summary(List.of("https://github.com/o/r/commit/" + SHA))).isNull();
        assertThat(fetched).isEmpty();
    }
}
