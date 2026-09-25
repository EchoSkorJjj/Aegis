package com.aegis.core.actions.pr;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import com.aegis.core.platform.common.ApiExceptions;

/** Validated proposal, not approval evidence. Creating one never performs a remote operation. */
public record PullRequestDraft(
        RepositoryRef repository, String baseBranch, String headBranch,
        String title, String body, String commitMessage, List<FileChange> changes) {

    public PullRequestDraft {
        if (repository == null) {
            throw new ApiExceptions.BadRequest("A repository is required");
        }
        baseBranch = baseBranch == null || baseBranch.isBlank() ? null : baseBranch;
        if (!safeBranch(headBranch) || !headBranch.startsWith("aegis/")
                || (baseBranch != null && !safeBranch(baseBranch)) || headBranch.equals(baseBranch)) {
            throw new ApiExceptions.BadRequest("A safe aegis/ head branch distinct from the base branch is required");
        }
        if (title == null || title.isBlank() || title.length() > 256) {
            throw new ApiExceptions.BadRequest("A PR title of at most 256 characters is required");
        }
        if (body == null || body.length() > 60000) {
            throw new ApiExceptions.BadRequest("A PR body of at most 60000 characters is required");
        }
        if (commitMessage == null || commitMessage.isBlank()) {
            throw new ApiExceptions.BadRequest("A commit message is required");
        }
        if (changes == null || changes.isEmpty() || changes.size() > 20 || changes.stream().anyMatch(Objects::isNull)) {
            throw new ApiExceptions.BadRequest("A draft PR requires 1–20 file changes");
        }
        var paths = new HashSet<String>();
        for (FileChange change : changes) {
            if (!paths.add(change.path())) {
                throw new ApiExceptions.BadRequest("A draft PR cannot change the same path twice");
            }
        }
        changes = List.copyOf(changes);
    }

    private static boolean safeBranch(String branch) {
        if (branch == null || !branch.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}")
                || branch.contains("..") || branch.contains("//") || branch.endsWith("/")
                || branch.endsWith(".")) {
            return false;
        }
        for (String segment : branch.split("/")) {
            if (segment.startsWith(".") || segment.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }
}
