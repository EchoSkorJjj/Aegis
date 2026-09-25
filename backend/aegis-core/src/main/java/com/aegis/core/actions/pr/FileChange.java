package com.aegis.core.actions.pr;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.aegis.core.platform.common.ApiExceptions;

/** Full UTF-8 file contents; immutable and checked before a provider can receive them. */
public record FileChange(String path, String content) {

    public static final int MAX_BYTES = 1024 * 1024;

    public FileChange {
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
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ApiExceptions.BadRequest("Each file must contain at most 1 MiB of UTF-8 content");
        }
    }
}
