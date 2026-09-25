package com.aegis.core.inventory;

import java.util.UUID;

import jakarta.validation.Valid;

import com.aegis.core.inventory.InventoryDtos.BusinessServiceResponse;
import com.aegis.core.inventory.InventoryDtos.ServiceRepositoryRequest;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.sbom.SbomGenerationProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Repository ownership is a composition fact, maintained under the same access rules as SBOMs. */
@RestController
@RequestMapping("/api/inventory/services/{serviceId}/repository")
@SecurityRequirement(name = "bearer-jwt")
public class ServiceRepositoryController {

    private final InventoryService inventory;
    private final SbomGenerationProperties properties;

    public ServiceRepositoryController(InventoryService inventory, SbomGenerationProperties properties) {
        this.inventory = inventory;
        this.properties = properties;
    }

    @PutMapping
    @PreAuthorize(InventoryRoles.COMPOSE)
    @Operation(summary = "Link or replace a service's repository and optional target branch")
    public BusinessServiceResponse linkRepository(
            @PathVariable UUID serviceId, @Valid @RequestBody ServiceRepositoryRequest request) {
        RepositoryUrl url = RepositoryUrl.parse(request.url(), properties.allowedHosts());
        return BusinessServiceResponse.from(inventory.linkServiceRepository(serviceId, url, request.branch()));
    }

    @DeleteMapping
    @PreAuthorize(InventoryRoles.COMPOSE)
    @Operation(summary = "Unlink a service's repository")
    public ResponseEntity<Void> unlinkRepository(@PathVariable UUID serviceId) {
        inventory.unlinkServiceRepository(serviceId);
        return ResponseEntity.noContent().build();
    }
}
