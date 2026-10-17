package com.aegis.core.inventory.sbom;

import java.util.UUID;

import jakarta.validation.Valid;

import com.aegis.core.inventory.InventoryRoles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generate a service's CycloneDX SBOM from its linked Git repository (via syft) and import it — the
 * automated counterpart to uploading a CycloneDX file (which stays available, e.g. for a CI pipeline).
 * Present only when SBOM generation is enabled ({@code aegis.inventory.sbom-generation.enabled}).
 */
@RestController
@RequestMapping("/api/inventory")
@SecurityRequirement(name = "bearer-jwt")
@ConditionalOnProperty(name = "aegis.inventory.sbom-generation.enabled", havingValue = "true", matchIfMissing = true)
public class SbomGenerationController {

    private final SbomGenerationService generation;

    public SbomGenerationController(SbomGenerationService generation) {
        this.generation = generation;
    }

    @PostMapping("/services/{serviceId}/sbom/from-repository")
    @PreAuthorize(InventoryRoles.COMPOSE)
    @Operation(summary = "Generate a CycloneDX SBOM from the service's linked repository (syft) and import it "
            + "(governance roles, or the service's owners); a token is only needed for a private repository "
            + "no host integration covers")
    public SbomImportResult importFromRepository(
            @PathVariable UUID serviceId, @Valid @RequestBody(required = false) RepositoryImportRequest request) {
        RepositoryImportRequest credentials = request == null ? new RepositoryImportRequest(null, null) : request;
        return generation.importFromRepository(serviceId, credentials.token(), credentials.username());
    }
}
