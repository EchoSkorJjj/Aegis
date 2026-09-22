package com.aegis.core.inventory.sbom;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to generate a service's SBOM straight from a source repository.
 *
 * @param repoUrl the {@code https://} repository URL (host must be on the server's allow-list, e.g. GitHub,
 *                GitLab, Bitbucket, or a self-hosted server)
 * @param ref     optional branch or tag to check out; the repository's default branch when omitted
 * @param token   optional access token (a personal-access / read token) for a PRIVATE repository; used
 *                transiently for the clone, sent only to that host, never stored or logged
 */
public record RepoSbomRequest(
        @NotBlank @Size(max = 400) String repoUrl,
        @Size(max = 200) String ref,
        @Size(max = 500) String token) {

    /** Redact the token so it never leaks through any logging that stringifies the request. */
    @Override
    public String toString() {
        return "RepoSbomRequest[repoUrl=" + repoUrl + ", ref=" + ref
                + ", token=" + (token == null || token.isBlank() ? "none" : "***") + "]";
    }
}
