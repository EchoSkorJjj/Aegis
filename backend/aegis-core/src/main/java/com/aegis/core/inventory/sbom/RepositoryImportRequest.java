package com.aegis.core.inventory.sbom;

import jakarta.validation.constraints.Size;

/**
 * Optional credentials for importing a service's SBOM from its linked repository. Neither is needed for a
 * public repository, or where a host integration such as the GitHub App grants read access.
 *
 * @param token    a read-only access token for a PRIVATE repository; used for this one clone, sent only to
 *                 the repository's host, never stored or logged
 * @param username sent with {@code token} where the host wants a particular one; defaults to the host's
 *                 convention (see {@link CloneCredential#forToken})
 */
public record RepositoryImportRequest(@Size(max = 500) String token, @Size(max = 100) String username) {

    /** Redact the token so it never leaks through any logging that stringifies the request. */
    @Override
    public String toString() {
        return "RepositoryImportRequest[token=" + (token == null || token.isBlank() ? "none" : "***")
                + ", username=" + username + "]";
    }
}
