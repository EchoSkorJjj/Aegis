package com.aegis.core.inventory.sbom;

import java.util.UUID;

import com.aegis.core.inventory.InventoryRoles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
@SecurityRequirement(name = "bearer-jwt")
public class SbomImportController {

    static final String CYCLONEDX_JSON = "application/vnd.cyclonedx+json";

    private final SbomImportService sboms;

    public SbomImportController(SbomImportService sboms) {
        this.sboms = sboms;
    }

    @PostMapping(path = "/services/{serviceId}/sbom", consumes = {MediaType.APPLICATION_JSON_VALUE, CYCLONEDX_JSON})
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Import a service's components and dependencies from a CycloneDX JSON SBOM")
    public SbomImportResult importSbom(@PathVariable UUID serviceId, @RequestBody CycloneDxBom bom) {
        return sboms.importSbom(serviceId, bom);
    }
}
