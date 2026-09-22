package com.aegis.core.inventory.sbom;

import java.util.UUID;

import com.aegis.core.inventory.InventoryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Orchestrates "import an SBOM straight from a repository": confirm the service exists (a fast,
 * org-scoped 404 before any cloning), generate the SBOM, then hand it to the same importer an uploaded
 * file uses. The clone and scan run outside any database transaction — only {@link SbomImportService}
 * opens one — so a slow network clone never holds a connection.
 */
@Service
@ConditionalOnProperty(name = "aegis.inventory.sbom-generation.enabled", havingValue = "true", matchIfMissing = true)
public class SbomGenerationService {

    private final InventoryService inventory;
    private final RepoSbomGenerator generator;
    private final SbomImportService importer;

    public SbomGenerationService(InventoryService inventory, RepoSbomGenerator generator, SbomImportService importer) {
        this.inventory = inventory;
        this.generator = generator;
        this.importer = importer;
    }

    public SbomImportResult importFromRepo(UUID serviceId, String repoUrl, String ref, String token) {
        inventory.getBusinessService(serviceId); // 404 (org-scoped) before any expensive work
        CycloneDxBom bom = generator.generate(repoUrl, ref, token);
        return importer.importSbom(serviceId, bom);
    }
}
