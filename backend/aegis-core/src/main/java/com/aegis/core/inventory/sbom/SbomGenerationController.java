package com.aegis.core.inventory.sbom;

import java.util.UUID;

import com.aegis.core.inventory.InventoryRoles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generate a service's CycloneDX SBOM directly from a Git repository (via syft) and import it — the
 * automated counterpart to uploading a hand-generated CycloneDX file. Present only when SBOM generation
 * is enabled ({@code aegis.inventory.sbom-generation.enabled}); the upload endpoint is always available.
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

    @PostMapping("/services/{serviceId}/sbom/from-repo")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Generate a CycloneDX SBOM from a Git repository (syft) and import it into the service")
    public SbomImportResult importFromRepo(@PathVariable UUID serviceId, @Valid @RequestBody RepoSbomRequest request) {
        return generation.importFromRepo(serviceId, request.repoUrl(), request.ref(), request.token());
    }
}
