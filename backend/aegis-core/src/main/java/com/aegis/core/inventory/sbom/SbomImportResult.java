package com.aegis.core.inventory.sbom;

import java.util.UUID;

/**
 * What an SBOM import changed. {@code componentsSkipped} counts components without a version. The dependency
 * edges are the service's own: {@code dependenciesCreated} new ones, and {@code dependenciesRemoved} those an
 * import from the repository no longer declares. {@code serviceComponentsUnlinked} counts the direct dependencies
 * it unlinked for the same reason. An upload only adds, so both are always 0 for one.
 */
public record SbomImportResult(
        UUID serviceId, int componentsCreated, int componentsMatched, int componentsSkipped,
        int dependenciesCreated, int dependenciesRemoved, int serviceComponentsLinked, int serviceComponentsUnlinked) {
}
