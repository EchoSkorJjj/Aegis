package com.aegis.core.inventory.sbom;

import java.util.UUID;

/** What an SBOM import changed. {@code componentsSkipped} counts components without a version. */
public record SbomImportResult(
        UUID serviceId, int componentsCreated, int componentsMatched, int componentsSkipped,
        int dependenciesCreated, int serviceComponentsLinked) {
}
