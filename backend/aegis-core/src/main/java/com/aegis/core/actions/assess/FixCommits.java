package com.aegis.core.actions.assess;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.aegis.core.advisories.CommitUrl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What an advisory's fix commits changed in the package's own code, for the AI to read beside the advisory (E11-5):
 * the industry finds a vulnerable function by the patch that fixed it. At most {@value #MAX_COMMITS} commits, each
 * fetched once ({@link DiffSource}; a commit never changes) and summarised ({@link FixDiffs}), the whole at most
 * {@value #MAX_CHARS} characters. Switched off with {@code aegis.assess.fix-commits.enabled}; a fetch that fails just
 * leaves its commit out.
 */
@Component
class FixCommits {

    static final int MAX_COMMITS = 3;
    static final int MAX_CHARS = 1200;
    private static final int CACHED = 256;

    private final boolean enabled;
    private final DiffSource source;
    private final Map<String, String> summaries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHED;
        }
    };

    FixCommits(@Value("${aegis.assess.fix-commits.enabled:true}") boolean enabled, DiffSource source) {
        this.enabled = enabled;
        this.source = source;
    }

    /** One line per changed source file across the commits, or null when there is nothing to say. */
    String summary(Collection<String> commitUrls) {
        if (!enabled || commitUrls == null) {
            return null;
        }
        Set<String> lines = new LinkedHashSet<>();
        commitUrls.stream().map(CommitUrl::parse).flatMap(java.util.Optional::stream).limit(MAX_COMMITS)
                .forEach(commit -> {
                    String summary = summaryOf(commit);
                    if (summary != null) {
                        summary.lines().forEach(lines::add);
                    }
                });
        String text = FixDiffs.joined(lines, MAX_CHARS);
        return text.isEmpty() ? null : text;
    }

    private String summaryOf(CommitUrl commit) {
        synchronized (summaries) {
            String known = summaries.get(commit.url());
            if (known != null) {
                return known;
            }
        }
        String summary = source.diff(commit.diffUri()).map(diff -> FixDiffs.summary(diff, MAX_CHARS)).orElse(null);
        if (summary != null) {
            synchronized (summaries) {
                summaries.put(commit.url(), summary);
            }
        }
        return summary;
    }
}
