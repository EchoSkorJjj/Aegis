package com.aegis.core.actions.pr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import com.aegis.core.platform.common.ApiExceptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PullRequestDraftTest {

    private static final RepositoryRef REPO = new RepositoryRef("github.com", "acme/payments");
    private static final List<FileChange> CHANGE = List.of(new FileChange("package.json", "{}"));

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../a", "a/../b", "/a", "a\\b", "a//b", "./a", "a/./b", "a/", "C:/a",
        ".github/workflows/test.yml", ".github/workflows/sub/test.yml", ".GITHUB/WORKFLOWS/test.yml",
        ".github/workflows", "a\u0000b", "a\nb"})
    void rejectsUnsafeFilePathsAndWorkflowEdits(String path) {
        assertThatThrownBy(() -> new FileChange(path, "content")).isInstanceOf(ApiExceptions.BadRequest.class);
    }

    @Test
    void acceptsNormalizedPathsAndMeasuresUtf8Bytes() {
        assertThatCode(() -> new FileChange("src/main/config.json", "")).doesNotThrowAnyException();
        assertThatCode(() -> new FileChange(".github/ISSUE_TEMPLATE/bug.md", "body")).doesNotThrowAnyException();
        assertThatCode(() -> new FileChange("lockfile", "a".repeat(FileChange.MAX_BYTES)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new FileChange("lockfile", "a".repeat(FileChange.MAX_BYTES + 1)))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> new FileChange("lockfile", "é".repeat(FileChange.MAX_BYTES / 2 + 1)))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> new FileChange("lockfile", null)).isInstanceOf(ApiExceptions.BadRequest.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"main", "aegis/", "other/upgrade", "aegis/../main", "aegis/a..b", "aegis/a//b",
        "aegis/a.", "aegis/.hidden", "aegis/a.lock", "aegis/a/b.lock", "aegis/a@{b", "aegis/a b", "-option"})
    void requiresASafeAegisHeadBranch(String head) {
        assertThatThrownBy(() -> draft("develop", head, "Title", "Body", CHANGE))
                .isInstanceOf(ApiExceptions.BadRequest.class);
    }

    @Test
    void rejectsSameBaseAndHeadAndUnsafeBaseButAllowsDefault() {
        assertThatThrownBy(() -> draft("aegis/update", "aegis/update", "Title", "Body", CHANGE))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> draft("-main", "aegis/update", "Title", "Body", CHANGE))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThat(draft(null, "aegis/update", "Title", "Body", CHANGE).baseBranch()).isNull();
        assertThatThrownBy(() -> draft(null, "aegis/" + "a".repeat(200), "Title", "Body", CHANGE))
                .isInstanceOf(ApiExceptions.BadRequest.class);
    }

    @Test
    void enforcesTextAndChangeCountLimitsAndKeepsDefensiveCopy() {
        assertThatCode(() -> draft(null, "aegis/update", "a".repeat(256), "b".repeat(60000), CHANGE))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> draft(null, "aegis/update", "a".repeat(257), "", CHANGE))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> draft(null, "aegis/update", "Title", "b".repeat(60001), CHANGE))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> draft(null, "aegis/update", " ", "", CHANGE))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> draft(null, "aegis/update", "Title", "", List.of()))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> draft(null, "aegis/update", "Title", "", null))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> draft(null, "aegis/update", "Title", "", Arrays.asList((FileChange) null)))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        var changes = new ArrayList<>(IntStream.range(0, 20).mapToObj(i -> new FileChange("file" + i, "")).toList());
        var draft = draft(null, "aegis/update", "Title", "", changes);
        changes.add(new FileChange("file20", ""));
        assertThat(draft.changes()).hasSize(20);
        assertThatThrownBy(() -> draft.changes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> draft(null, "aegis/update", "Title", "", changes))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> draft(null, "aegis/update", "Title", "", List.of(CHANGE.getFirst(), CHANGE.getFirst())))
                .isInstanceOf(ApiExceptions.BadRequest.class);
    }

    @Test
    void refusesInvalidRepositoryOrCommitMessage() {
        assertThatThrownBy(() -> new RepositoryRef("https://github.com", "a/b"))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> new RepositoryRef("github.com", "a/../b"))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> new PullRequestDraft(null, null, "aegis/update", "Title", "", "Commit", CHANGE))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> new PullRequestDraft(REPO, null, "aegis/update", "Title", "", " ", CHANGE))
                .isInstanceOf(ApiExceptions.BadRequest.class);
    }

    private static PullRequestDraft draft(String base, String head, String title, String body, List<FileChange> changes) {
        return new PullRequestDraft(REPO, base, head, title, body, "Update dependency", changes);
    }
}
