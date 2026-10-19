package com.aegis.core.actions.pr;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.aegis.core.platform.common.ApiExceptions;

/**
 * Full UTF-8 file contents; immutable and checked before a provider can receive them.
 *
 * <p>{@code expectedRevision}, when set, is the revision the change was computed from (see
 * {@link RepositoryFile}): the provider must refuse the write if the file has moved on since.
 */
public record FileChange(String path, String content, String expectedRevision) {

    public static final int MAX_BYTES = 1024 * 1024;

    /** A change with no revision precondition — a new file, or one the caller did not read first. */
    public FileChange(String path, String content) {
        this(path, content, null);
    }

    public FileChange {
        requireSafePath(path);
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ApiExceptions.BadRequest("Each file must contain at most 1 MiB of UTF-8 content");
        }
        if (expectedRevision != null && !expectedRevision.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new ApiExceptions.BadRequest("An expected file revision must be a plain revision id");
        }
    }

    /** A relative, normalised path outside {@code .github/workflows/}; throws otherwise. */
    public static void requireSafePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.contains(":") || path.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiExceptions.BadRequest("File paths must be relative and normalized");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new ApiExceptions.BadRequest("File paths must be relative and normalized");
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.equals(".github/workflows") || lower.startsWith(".github/workflows/")) {
            throw new ApiExceptions.BadRequest("Draft PRs cannot change .github/workflows/");
        }
    }
}
