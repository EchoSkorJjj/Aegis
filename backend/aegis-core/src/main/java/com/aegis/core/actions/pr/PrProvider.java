package com.aegis.core.actions.pr;

/** VCS-independent draft PR port. Only a future human approval flow may invoke it. */
public interface PrProvider {

    String id();

    boolean supports(RepositoryRef repository);

    /**
     * Open a draft after caller authorization. If the base is omitted, resolve the repository default
     * and refuse it if it equals the head before any write. Implementations must never merge or approve.
     */
    PullRequestRef openDraftPullRequest(PullRequestDraft draft);
}
