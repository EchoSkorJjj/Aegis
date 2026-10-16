package com.aegis.core.advisories;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A commit an advisory references — on github.com or gitlab.com — most often the one that fixed it (E11-5). An advisory
 * is untrusted, so only a URL that is plainly a commit on one of those hosts counts, and it is rebuilt from its parts
 * (no credentials, port, query or fragment survive); {@link #diffUri()} is the only thing ever fetched from it.
 */
public record CommitUrl(String host, String repository, String sha) {

    /** At most this many of an advisory's commits are kept: the fix, and its backports. */
    public static final int MAX_PER_ADVISORY = 5;

    private static final String SEGMENT = "(?!\\.{1,2}/)[A-Za-z0-9_.-]{1,100}";
    private static final Pattern GITHUB = Pattern.compile("https?://(?:www\\.)?github\\.com/(" + SEGMENT + "/"
            + SEGMENT + ")/(?:pull/[0-9]{1,10}/)?commits?/([0-9a-fA-F]{7,40})(?:[/?#].*)?");
    private static final Pattern GITLAB = Pattern.compile("https?://gitlab\\.com/((?:" + SEGMENT + "/){1,5}" + SEGMENT
            + ")/-/commit/([0-9a-fA-F]{7,40})(?:[/?#].*)?");

    /** The commit {@code url} names, or empty when it isn't plainly a commit on github.com or gitlab.com. */
    public static Optional<CommitUrl> parse(String url) {
        if (url == null) {
            return Optional.empty();
        }
        String trimmed = url.strip();
        Matcher github = GITHUB.matcher(trimmed);
        if (github.matches()) {
            return Optional.of(new CommitUrl("github.com", github.group(1), github.group(2).toLowerCase(Locale.ROOT)));
        }
        Matcher gitlab = GITLAB.matcher(trimmed);
        if (gitlab.matches()) {
            return Optional.of(new CommitUrl("gitlab.com", gitlab.group(1), gitlab.group(2).toLowerCase(Locale.ROOT)));
        }
        return Optional.empty();
    }

    /** The commits among an advisory's reference URLs, canonical, once each, at most {@value #MAX_PER_ADVISORY}. */
    public static List<String> among(Collection<String> urls) {
        List<String> commits = new ArrayList<>();
        if (urls == null) {
            return commits;
        }
        for (String url : urls) {
            parse(url).map(CommitUrl::url).filter(commit -> !commits.contains(commit)).ifPresent(commits::add);
            if (commits.size() == MAX_PER_ADVISORY) {
                break;
            }
        }
        return commits;
    }

    /** The commit's page, as kept on the advisory. */
    public String url() {
        return "github.com".equals(host)
                ? "https://github.com/" + repository + "/commit/" + sha
                : "https://gitlab.com/" + repository + "/-/commit/" + sha;
    }

    /** The commit as a unified diff, which both hosts serve beside its page. */
    public URI diffUri() {
        return URI.create(url() + ".diff");
    }
}
