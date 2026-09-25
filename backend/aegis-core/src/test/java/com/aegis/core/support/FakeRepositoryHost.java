package com.aegis.core.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.aegis.core.actions.pr.FileChange;
import com.aegis.core.actions.pr.PrProvider;
import com.aegis.core.actions.pr.PullRequestDraft;
import com.aegis.core.actions.pr.PullRequestRef;
import com.aegis.core.actions.pr.RepositoryFile;
import com.aegis.core.actions.pr.RepositoryRef;
import com.aegis.core.platform.common.ApiExceptions;

/**
 * An in-memory gitlab.com for the integration suite: files with revisions, like a real host, so an edit
 * is refused when the file moved on after it was read; drafts are recorded instead of opened. Registered
 * once in the shared test context ({@link ActionsTestConfig}); tests use unique repositories. Not
 * github.com, so the suite still boots with no provider for the host the real (disabled) adapter serves.
 */
public class FakeRepositoryHost implements PrProvider {

    private final Map<String, RepositoryFile> files = new ConcurrentHashMap<>();
    private final List<PullRequestDraft> opened = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger revisions = new AtomicInteger();
    private final AtomicInteger numbers = new AtomicInteger();
    private volatile RuntimeException nextFailure;

    /** Put (or replace) a file on the repository's default branch, with a fresh revision. */
    public void put(String repositoryPath, String path, String content) {
        String revision = "%040x".formatted(revisions.incrementAndGet());
        files.put(repositoryPath + ":" + path, new RepositoryFile(path, content, revision));
    }

    /** Make the next draft pull request fail with {@code failure}, as a host outage would. */
    public void failNextOpen(RuntimeException failure) {
        nextFailure = failure;
    }

    /** The drafts opened against one repository, oldest first. */
    public List<PullRequestDraft> opened(String repositoryPath) {
        synchronized (opened) {
            return opened.stream().filter(draft -> draft.repository().path().equals(repositoryPath)).toList();
        }
    }

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public boolean supports(RepositoryRef repository) {
        return "gitlab.com".equals(repository.host());
    }

    @Override
    public RepositoryFile readFile(RepositoryRef repository, String branch, String path) {
        RepositoryFile file = files.get(repository.path() + ":" + path);
        if (file == null) {
            throw new ApiExceptions.NotFound(path + " was not found in the repository");
        }
        return file;
    }

    @Override
    public PullRequestRef openDraftPullRequest(PullRequestDraft draft) {
        RuntimeException failure = nextFailure;
        if (failure != null) {
            nextFailure = null;
            throw failure;
        }
        for (FileChange change : draft.changes()) {
            RepositoryFile current = files.get(draft.repository().path() + ":" + change.path());
            if (change.expectedRevision() != null
                    && (current == null || !current.revision().equals(change.expectedRevision()))) {
                throw new ApiExceptions.Conflict("The host rejected the change; the file changed since it was read");
            }
        }
        opened.add(draft);
        String number = Integer.toString(numbers.incrementAndGet());
        return new PullRequestRef(id(), "https://gitlab.com/" + draft.repository().path() + "/pull/" + number,
                number, draft.headBranch());
    }
}
