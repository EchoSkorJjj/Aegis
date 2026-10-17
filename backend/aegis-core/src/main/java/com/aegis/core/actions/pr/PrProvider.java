package com.aegis.core.actions.pr;

/** VCS-independent draft PR port. Only a human approval flow may invoke it. */
public interface PrProvider {

    String id();

    boolean supports(RepositoryRef repository);

    /**
     * Read one file as it is now on {@code branch} ({@code null}: the repository's default branch), with
     * its revision, so the change built from it can require the file to be unchanged when written.
     */
    RepositoryFile readFile(RepositoryRef repository, String branch, String path);

    /**
     * Open a draft after caller authorization. If the base is omitted, resolve the repository default
     * and refuse it if it equals the head before any write. A change carrying an
     * {@link FileChange#expectedRevision()} is written only over that exact revision. Implementations
     * must never merge or approve.
     */
    PullRequestRef openDraftPullRequest(PullRequestDraft draft);
}
