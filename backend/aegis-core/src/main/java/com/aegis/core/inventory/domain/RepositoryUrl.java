package com.aegis.core.inventory.domain;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aegis.core.platform.common.ApiExceptions;

/**
 * A canonical HTTPS repository location, validated against the configured host allow-list. Accepts what
 * people paste: an HTTPS URL, an SSH clone URL ({@code git@host:owner/repo.git}, {@code ssh://git@host/…}),
 * a clone URL carrying a username ({@code https://user@bitbucket.org/…}), or a browser link into a branch or
 * file — each reduced to the repository itself. A password in the URL is refused rather than stored.
 */
public final class RepositoryUrl {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");
    /** {@code git@github.com:owner/repo.git} — git's scp-like SSH syntax. */
    private static final Pattern SCP_LIKE = Pattern.compile("[A-Za-z0-9._-]+@([A-Za-z0-9.-]+):(?!/)(.+)");
    /** Hosts whose repositories are always owner/repository, so anything after is a page within one. */
    private static final List<String> TWO_SEGMENT_HOSTS = List.of("github.com", "bitbucket.org");

    private final String host;
    private final String path;

    private RepositoryUrl(String host, String path) {
        this.host = host;
        this.path = path;
    }

    public static RepositoryUrl parse(String raw, List<String> allowedHosts) {
        if (raw == null || raw.isBlank()) {
            throw new ApiExceptions.BadRequest("A repository URL is required");
        }
        if (raw.length() > 400) {
            throw new ApiExceptions.BadRequest("The repository URL must be at most 400 characters");
        }
        String trimmed = raw.trim();
        Matcher scpLike = SCP_LIKE.matcher(trimmed);
        URI uri;
        try {
            uri = scpLike.matches() ? URI.create("ssh://git@" + scpLike.group(1) + "/" + scpLike.group(2))
                    : URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new ApiExceptions.BadRequest("That is not a valid repository URL");
        }
        // SSH names the same repository; Aegis reads it over HTTPS on the host's standard port.
        boolean ssh = "ssh".equalsIgnoreCase(uri.getScheme());
        if (!ssh && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ApiExceptions.BadRequest("Use the repository's https:// or SSH clone URL");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (allowedHosts == null || allowedHosts.stream().noneMatch(host::equalsIgnoreCase)) {
            throw new ApiExceptions.BadRequest("This host is not allowed. Allowed hosts: " + allowedHosts);
        }
        // A bare username (Bitbucket puts yours in its clone URL) is dropped; a password is never accepted.
        if ((uri.getRawUserInfo() != null && uri.getRawUserInfo().contains(":"))
                || (!ssh && uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new ApiExceptions.BadRequest("Repository URLs must not contain credentials or a custom port");
        }
        // Accept what a browser's address bar shows: a query or fragment (?tab=readme-ov-file, #readme)
        // and empty segments are ignored, as the SBOM importer always did. The canonical form carries none.
        String[] segments = Arrays.stream((uri.getRawPath() == null ? "" : uri.getRawPath()).split("/"))
                .filter(segment -> !segment.isEmpty())
                .toArray(String[]::new);
        // A dot segment anywhere would be resolved by a browser before the page below is dropped.
        for (String segment : segments) {
            String decoded = segment.replaceAll("(?i)%2e", ".");
            if (decoded.equals(".") || decoded.equals("..")) {
                throw new ApiExceptions.BadRequest("The repository path contains unsupported characters");
            }
        }
        segments = repositorySegments(host, segments);
        if (segments.length < 2) {
            throw new ApiExceptions.BadRequest("Expected a URL like https://" + host + "/owner/repository");
        }
        String last = segments[segments.length - 1];
        if (last.endsWith(".git")) {
            segments[segments.length - 1] = last.substring(0, last.length() - 4);
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new ApiExceptions.BadRequest("The repository path contains unsupported characters");
            }
        }
        return new RepositoryUrl(host, String.join("/", segments));
    }

    /**
     * The segments naming the repository, without a page inside it: GitLab separates the two with a
     * {@code -} segment on any host ({@code group/sub/repo/-/tree/main}); GitHub and Bitbucket
     * repositories are always two segments ({@code owner/repo/tree/main}, {@code workspace/repo/src/main}).
     */
    private static String[] repositorySegments(String host, String[] segments) {
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].equals("-")) {
                return Arrays.copyOf(segments, i);
            }
        }
        if (TWO_SEGMENT_HOSTS.contains(host) && segments.length > 2) {
            return Arrays.copyOf(segments, 2);
        }
        return segments;
    }

    /** Null/blank selects the repository default; otherwise accept a safe Git branch or tag. */
    public static String normalizeRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String trimmed = ref.trim();
        if (!REF.matcher(trimmed).matches() || trimmed.contains("..") || trimmed.contains("//")
                || trimmed.endsWith("/") || trimmed.endsWith(".")) {
            throw new ApiExceptions.BadRequest("That branch or tag name is not allowed");
        }
        for (String segment : trimmed.split("/")) {
            if (segment.startsWith(".") || segment.endsWith(".lock")) {
                throw new ApiExceptions.BadRequest("That branch or tag name is not allowed");
            }
        }
        return trimmed;
    }

    public String host() {
        return host;
    }

    public String path() {
        return path;
    }

    public String canonical() {
        return "https://" + host + "/" + path;
    }
}
