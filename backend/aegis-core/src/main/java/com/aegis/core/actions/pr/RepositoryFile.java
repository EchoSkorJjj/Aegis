package com.aegis.core.actions.pr;

/**
 * One file as a provider read it: its UTF-8 content and the provider's revision of it (a Git blob SHA on
 * GitHub). Passing the revision back as {@link FileChange#expectedRevision()} makes the write refuse if
 * the file changed after it was read, so an edit is never computed from one version and written over
 * another.
 */
public record RepositoryFile(String path, String content, String revision) {
}
