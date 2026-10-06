package com.aegis.core.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.aegis.core.actions.tracker.IssueTracker;
import com.aegis.core.platform.common.ApiExceptions;

/**
 * An in-memory issue tracker: records every issue filed, so an integration test can see exactly what a
 * person approved, and can be made to refuse — as a tracker the organization has not connected would.
 */
public class FakeIssueTracker implements IssueTracker {

    public static final String NOT_CONNECTED = "Jira isn't connected to this organization";

    private final List<IssueDraft> filed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger keys = new AtomicInteger(100);
    private volatile boolean connected = true;

    /** Refuse to file, as an organization without a connected tracker gets, until reconnected. */
    public void connected(boolean connected) {
        this.connected = connected;
    }

    public List<IssueDraft> filed() {
        synchronized (filed) {
            return List.copyOf(filed);
        }
    }

    @Override
    public FiledIssue file(IssueDraft issue) {
        if (!connected) {
            throw new ApiExceptions.Conflict(NOT_CONNECTED);
        }
        filed.add(issue);
        String key = "AEG-" + keys.incrementAndGet();
        return new FiledIssue(key, "https://tracker.example/browse/" + key);
    }
}
