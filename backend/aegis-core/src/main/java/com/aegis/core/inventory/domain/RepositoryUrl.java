package com.aegis.core.inventory.domain;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.aegis.core.platform.common.ApiExceptions;

/** A canonical HTTPS repository location, validated against the configured host allow-list. */
public final class RepositoryUrl {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");

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
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiExceptions.BadRequest("That is not a valid repository URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ApiExceptions.BadRequest("Only https:// repository URLs are supported");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (allowedHosts == null || allowedHosts.stream().noneMatch(host::equalsIgnoreCase)) {
            throw new ApiExceptions.BadRequest("This host is not allowed. Allowed hosts: " + allowedHosts);
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new ApiExceptions.BadRequest("Repository URLs must not contain credentials, query, fragment or a custom port");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        String[] segments = path.split("/", -1);
        if (segments.length < 2) {
            throw new ApiExceptions.BadRequest("Expected a URL like https://" + host + "/owner/repository");
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new ApiExceptions.BadRequest("The repository path contains unsupported characters");
            }
        }
        return new RepositoryUrl(host, path);
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
