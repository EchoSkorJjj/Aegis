package com.aegis.core.inventory.sbom;

import com.aegis.core.inventory.domain.RepositoryUrl;

/**
 * Read access to a repository granted by a host integration rather than a pasted token — the GitHub App
 * today. Each integration implements this port next to the rest of its code ({@code actions.pr.github}), so
 * the inventory depends on none of them; with no integration enabled there is simply no implementation.
 */
public interface RepositoryReadAccess {

    /** Read access to {@code repository} for the caller's organization, or why this integration can't give it. */
    ReadGrant grant(RepositoryUrl repository);

    /**
     * A credential and {@code via}, what granted it; or no credential, with {@code unavailable} saying why
     * when the integration covers the host but not this repository or organization — and null when it
     * doesn't cover the host at all.
     */
    record ReadGrant(CloneCredential credential, String via, String unavailable) {

        public static ReadGrant granted(CloneCredential credential, String via) {
            return new ReadGrant(credential, via, null);
        }

        public static ReadGrant unavailable(String reason) {
            return new ReadGrant(null, null, reason);
        }

        public static ReadGrant notCovered() {
            return new ReadGrant(null, null, null);
        }
    }
}
