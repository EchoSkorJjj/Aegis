package com.aegis.core.actions.pr;

import java.util.ArrayList;
import java.util.List;

/** Test-only adapter: captures proposals without opening a network connection. */
final class RecordingPrProvider implements PrProvider {

    final List<PullRequestDraft> received = new ArrayList<>();
    private final String host;

    RecordingPrProvider(String host) {
        this.host = host;
    }

    @Override
    public String id() {
        return "recording";
    }

    @Override
    public boolean supports(RepositoryRef repository) {
        return repository.host().equals(host);
    }

    @Override
    public RepositoryFile readFile(RepositoryRef repository, String branch, String path) {
        throw new UnsupportedOperationException("the recording provider serves no files");
    }

    @Override
    public PullRequestRef openDraftPullRequest(PullRequestDraft draft) {
        received.add(draft);
        return new PullRequestRef(id(), "https://" + host + "/" + draft.repository().path() + "/pull/1",
                "1", draft.headBranch());
    }
}
