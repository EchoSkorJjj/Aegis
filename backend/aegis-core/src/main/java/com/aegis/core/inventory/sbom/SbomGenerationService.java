package com.aegis.core.inventory.sbom;

import java.util.UUID;

import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.domain.SbomImportSource;
import com.aegis.core.platform.common.ApiExceptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Orchestrates "import a service's SBOM from its repository": confirm the service exists and has a linked
 * repository (a fast, org-scoped answer before any cloning), work out how to read it, generate the SBOM,
 * then hand it to the same importer an uploaded file uses. The repository is always the service's own link,
 * never a caller's input. The clone and scan run outside any database transaction — only
 * {@link SbomImportService} opens one — so a slow network clone never holds a connection.
 *
 * <p>Access, first that applies: a token the caller pasted (used for this import, never stored); a host
 * integration's credential ({@link RepositoryReadAccess}, e.g. the GitHub App on the repository's
 * account); otherwise none, which is all a public repository needs.
 */
@Service
@ConditionalOnProperty(name = "aegis.inventory.sbom-generation.enabled", havingValue = "true", matchIfMissing = true)
public class SbomGenerationService {

    private final InventoryService inventory;
    private final RepoSbomGenerator generator;
    private final SbomImportService importer;
    private final SbomGenerationProperties properties;
    private final RepositoryAccess access;

    SbomGenerationService(InventoryService inventory, RepoSbomGenerator generator, SbomImportService importer,
            SbomGenerationProperties properties, RepositoryAccess access) {
        this.inventory = inventory;
        this.generator = generator;
        this.importer = importer;
        this.properties = properties;
        this.access = access;
    }

    public SbomImportResult importFromRepository(UUID serviceId, String token, String username) {
        BusinessService service = inventory.getBusinessService(serviceId); // 404 (org-scoped) before any work
        if (service.getRepositoryUrl() == null) {
            throw new ApiExceptions.Conflict("Link the service's repository before importing its SBOM from it");
        }
        // Re-validated against the current allow-list: a host removed since the link was saved is refused.
        RepositoryUrl repository = RepositoryUrl.parse(service.getRepositoryUrl(), properties.allowedHosts());
        CloneAccess access = this.access.access(repository, token, username);
        RepoSbomGenerator.RepositoryScan scan = generator.read(repository, service.getRepositoryBranch(), access);
        return importer.importRepository(serviceId, scan.layout(), switch (access.source()) {
            case TOKEN -> SbomImportSource.TOKEN;
            case INTEGRATION -> SbomImportSource.INTEGRATION;
            case ANONYMOUS -> SbomImportSource.PUBLIC;
        }, scan.imports());
    }
}
