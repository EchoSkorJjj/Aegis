package com.aegis.core.inventory.sbom;

/**
 * How one clone authenticates — a pasted token, an integration's credential, or none — and what a refusal
 * should tell the person importing: {@code detail} is the integration that granted access, or why none could.
 */
record CloneAccess(CloneCredential credential, Source source, String detail) {

    enum Source { TOKEN, INTEGRATION, ANONYMOUS }

    static CloneAccess token(CloneCredential credential) {
        return new CloneAccess(credential, Source.TOKEN, null);
    }

    static CloneAccess integration(CloneCredential credential, String via) {
        return new CloneAccess(credential, Source.INTEGRATION, via);
    }

    /** No credential; {@code unavailable} is why the enabled integrations couldn't provide one (null if none tried). */
    static CloneAccess anonymous(String unavailable) {
        return new CloneAccess(null, Source.ANONYMOUS, unavailable);
    }
}
