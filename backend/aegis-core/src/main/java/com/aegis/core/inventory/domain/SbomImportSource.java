package com.aegis.core.inventory.domain;

/**
 * How a service's SBOM was last imported — never the credential itself. A repository import records how
 * it got read access, so the next one can ask for a token up front when this one needed it.
 */
public enum SbomImportSource {
    /** A CycloneDX file posted to the API (a CI pipeline, the demo seed). */
    UPLOAD,
    /** The linked repository, cloned without credentials. */
    PUBLIC,
    /** The linked repository, read with a host integration's credential (the GitHub App). */
    INTEGRATION,
    /** The linked repository, read with a pasted access token (used once, not stored). */
    TOKEN
}
