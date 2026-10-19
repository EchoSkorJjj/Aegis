package com.aegis.core.inventory;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.aegis.core.inventory.InventoryDtos.AssignOwnerRequest;
import com.aegis.core.inventory.InventoryDtos.BusinessServiceRequest;
import com.aegis.core.inventory.InventoryDtos.BusinessServiceResponse;
import com.aegis.core.inventory.InventoryDtos.NamedInventoryRequest;
import com.aegis.core.inventory.InventoryDtos.OwnerResponse;
import com.aegis.core.inventory.InventoryDtos.PackageUsageResponse;
import com.aegis.core.inventory.InventoryDtos.SoftwareComponentRequest;
import com.aegis.core.inventory.InventoryDtos.SoftwareComponentResponse;
import com.aegis.core.inventory.InventoryDtos.ThirdPartyVendorResponse;
import com.aegis.core.inventory.InventoryDtos.VendorUsageResponse;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
@SecurityRequirement(name = "bearer-jwt")
public class InventoryController {

    private final InventoryService inventory;
    private final UsedByService usedBy;

    public InventoryController(InventoryService inventory, UsedByService usedBy) {
        this.inventory = inventory;
        this.usedBy = usedBy;
    }

    @PostMapping("/services")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Create a business service")
    public ResponseEntity<BusinessServiceResponse> createBusinessService(
            @Valid @RequestBody BusinessServiceRequest request) {
        BusinessServiceResponse response = BusinessServiceResponse.from(
                inventory.createBusinessService(request.name(), request.criticality(), request.exposure()));
        return ResponseEntity.created(uri("/api/inventory/services/" + response.id())).body(response);
    }

    @GetMapping("/services")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List business services, optionally filtered by name, criticality, exposure, and created date")
    public Page<BusinessServiceResponse> listBusinessServices(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) BusinessCriticality criticality,
            @RequestParam(required = false) ServiceExposure exposure,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = {"name", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        InventoryFilters.ServiceFilter filter =
                new InventoryFilters.ServiceFilter(search, criticality, exposure, from, to);
        return inventory.listBusinessServices(filter, pageable).map(BusinessServiceResponse::from);
    }

    @GetMapping("/services/{id}")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Get a business service")
    public BusinessServiceResponse getBusinessService(@PathVariable UUID id) {
        return BusinessServiceResponse.from(inventory.getBusinessService(id));
    }

    @PutMapping("/services/{id}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Replace a business service")
    public BusinessServiceResponse updateBusinessService(
            @PathVariable UUID id, @Valid @RequestBody BusinessServiceRequest request) {
        return BusinessServiceResponse.from(
                inventory.updateBusinessService(id, request.name(), request.criticality(), request.exposure()));
    }

    @DeleteMapping("/services/{id}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Delete a business service and its relationship edges")
    public ResponseEntity<Void> deleteBusinessService(@PathVariable UUID id) {
        inventory.deleteBusinessService(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/components")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Create a software component")
    public ResponseEntity<SoftwareComponentResponse> createSoftwareComponent(
            @Valid @RequestBody SoftwareComponentRequest request) {
        SoftwareComponentResponse response = SoftwareComponentResponse.from(inventory.createSoftwareComponent(
                request.ecosystem(), request.name(), request.version()));
        return ResponseEntity.created(uri("/api/inventory/components/" + response.id())).body(response);
    }

    @GetMapping("/components")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List software components, optionally filtered by name/version, ecosystem, and created date")
    public Page<SoftwareComponentResponse> listSoftwareComponents(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String ecosystem,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID excludeLinkedToService,
            @PageableDefault(size = 20, sort = {"name", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        InventoryFilters.ComponentFilter filter =
                new InventoryFilters.ComponentFilter(search, ecosystem, from, to, excludeLinkedToService);
        return inventory.listSoftwareComponents(filter, pageable).map(SoftwareComponentResponse::from);
    }

    @GetMapping("/components/ecosystems")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List the distinct component ecosystems in the org — powers the ecosystem filter")
    public List<String> listComponentEcosystems() {
        return inventory.listComponentEcosystems();
    }

    @GetMapping("/components/{id}")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Get a software component")
    public SoftwareComponentResponse getSoftwareComponent(@PathVariable UUID id) {
        return SoftwareComponentResponse.from(inventory.getSoftwareComponent(id));
    }

    @PutMapping("/components/{id}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Replace a software component")
    public SoftwareComponentResponse updateSoftwareComponent(
            @PathVariable UUID id, @Valid @RequestBody SoftwareComponentRequest request) {
        return SoftwareComponentResponse.from(inventory.updateSoftwareComponent(
                id, request.ecosystem(), request.name(), request.version()));
    }

    @DeleteMapping("/components/{id}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Delete a software component and its relationship edges")
    public ResponseEntity<Void> deleteSoftwareComponent(@PathVariable UUID id) {
        inventory.deleteSoftwareComponent(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/vendors")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Create a third-party vendor")
    public ResponseEntity<ThirdPartyVendorResponse> createThirdPartyVendor(
            @Valid @RequestBody NamedInventoryRequest request) {
        ThirdPartyVendorResponse response = ThirdPartyVendorResponse.from(
                inventory.createThirdPartyVendor(request.name()));
        return ResponseEntity.created(uri("/api/inventory/vendors/" + response.id())).body(response);
    }

    @GetMapping("/vendors")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List third-party vendors, optionally filtered by name and created date")
    public Page<ThirdPartyVendorResponse> listThirdPartyVendors(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID excludeLinkedToService,
            @PageableDefault(size = 20, sort = {"name", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        InventoryFilters.NamedFilter filter = new InventoryFilters.NamedFilter(search, from, to, excludeLinkedToService);
        return inventory.listThirdPartyVendors(filter, pageable).map(ThirdPartyVendorResponse::from);
    }

    @GetMapping("/vendors/{id}")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Get a third-party vendor")
    public ThirdPartyVendorResponse getThirdPartyVendor(@PathVariable UUID id) {
        return ThirdPartyVendorResponse.from(inventory.getThirdPartyVendor(id));
    }

    @PutMapping("/vendors/{id}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Replace a third-party vendor")
    public ThirdPartyVendorResponse updateThirdPartyVendor(
            @PathVariable UUID id, @Valid @RequestBody NamedInventoryRequest request) {
        return ThirdPartyVendorResponse.from(inventory.updateThirdPartyVendor(id, request.name()));
    }

    @DeleteMapping("/vendors/{id}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Delete a third-party vendor and its relationship edges")
    public ResponseEntity<Void> deleteThirdPartyVendor(@PathVariable UUID id) {
        inventory.deleteThirdPartyVendor(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/owners")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List the people assigned as owners of at least one service, optionally filtered "
            + "by name/email and created date")
    public Page<OwnerResponse> listOwners(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID excludeLinkedToService,
            @PageableDefault(size = 20, sort = {"name", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        InventoryFilters.NamedFilter filter = new InventoryFilters.NamedFilter(search, from, to, excludeLinkedToService);
        return inventory.listOwners(filter, pageable).map(OwnerResponse::from);
    }

    @GetMapping("/owners/{id}")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Get an owner")
    public OwnerResponse getOwner(@PathVariable UUID id) {
        return OwnerResponse.from(inventory.getOwner(id));
    }

    @GetMapping("/services/{serviceId}/components")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List a business service's components")
    public Page<SoftwareComponentResponse> listServiceComponents(
            @PathVariable UUID serviceId,
            @PageableDefault(size = 20, sort = {"name", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        return inventory.listServiceComponents(serviceId, pageable).map(SoftwareComponentResponse::from);
    }

    @PostMapping("/services/{serviceId}/components/{componentId}")
    @PreAuthorize(InventoryRoles.COMPOSE)
    @Operation(summary = "Link a component to a business service (governance roles, or the service's owners)")
    public ResponseEntity<Void> addServiceComponent(
            @PathVariable UUID serviceId, @PathVariable UUID componentId) {
        inventory.addServiceComponent(serviceId, componentId);
        return relationshipCreated("/api/inventory/services/" + serviceId + "/components/" + componentId);
    }

    @DeleteMapping("/services/{serviceId}/components/{componentId}")
    @PreAuthorize(InventoryRoles.COMPOSE)
    @Operation(summary = "Unlink a component from a business service (governance roles, or the service's owners)")
    public ResponseEntity<Void> removeServiceComponent(
            @PathVariable UUID serviceId, @PathVariable UUID componentId) {
        inventory.removeServiceComponent(serviceId, componentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/services/{serviceId}/vendors")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List a business service's vendors")
    public Page<ThirdPartyVendorResponse> listServiceVendors(
            @PathVariable UUID serviceId,
            @PageableDefault(size = 20, sort = {"name", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        return inventory.listServiceVendors(serviceId, pageable).map(ThirdPartyVendorResponse::from);
    }

    @PostMapping("/services/{serviceId}/vendors/{vendorId}")
    @PreAuthorize(InventoryRoles.COMPOSE)
    @Operation(summary = "Link a vendor to a business service (governance roles, or the service's owners)")
    public ResponseEntity<Void> addServiceVendor(@PathVariable UUID serviceId, @PathVariable UUID vendorId) {
        inventory.addServiceVendor(serviceId, vendorId);
        return relationshipCreated("/api/inventory/services/" + serviceId + "/vendors/" + vendorId);
    }

    @DeleteMapping("/services/{serviceId}/vendors/{vendorId}")
    @PreAuthorize(InventoryRoles.COMPOSE)
    @Operation(summary = "Unlink a vendor from a business service (governance roles, or the service's owners)")
    public ResponseEntity<Void> removeServiceVendor(@PathVariable UUID serviceId, @PathVariable UUID vendorId) {
        inventory.removeServiceVendor(serviceId, vendorId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/services/{serviceId}/owners")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List a business service's owners")
    public Page<OwnerResponse> listServiceOwners(
            @PathVariable UUID serviceId,
            @PageableDefault(size = 20, sort = {"name", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        return inventory.listServiceOwners(serviceId, pageable).map(OwnerResponse::from);
    }

    @PostMapping("/services/{serviceId}/owners")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Assign an organization member holding the Service Owner role as one of a service's "
            + "owners (verified against identity-service)")
    public ResponseEntity<OwnerResponse> assignServiceOwner(
            @PathVariable UUID serviceId, @Valid @RequestBody AssignOwnerRequest request) {
        OwnerResponse response = OwnerResponse.from(inventory.assignServiceOwner(serviceId, request.userId()));
        return ResponseEntity.created(uri("/api/inventory/services/" + serviceId + "/owners/" + response.id()))
                .body(response);
    }

    @DeleteMapping("/services/{serviceId}/owners/{ownerId}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Unlink an owner from a business service")
    public ResponseEntity<Void> removeServiceOwner(@PathVariable UUID serviceId, @PathVariable UUID ownerId) {
        inventory.removeServiceOwner(serviceId, ownerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/components/{componentId}/dependencies")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List a component's direct dependencies")
    public Page<SoftwareComponentResponse> listComponentDependencies(
            @PathVariable UUID componentId,
            @PageableDefault(size = 20, sort = {"name", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        return inventory.listComponentDependencies(componentId, pageable).map(SoftwareComponentResponse::from);
    }

    @PostMapping("/components/{componentId}/dependencies/{dependencyId}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Add a direct software-component dependency")
    public ResponseEntity<Void> addComponentDependency(
            @PathVariable UUID componentId, @PathVariable UUID dependencyId) {
        inventory.addComponentDependency(componentId, dependencyId);
        return relationshipCreated(
                "/api/inventory/components/" + componentId + "/dependencies/" + dependencyId);
    }

    @DeleteMapping("/components/{componentId}/dependencies/{dependencyId}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Remove a direct software-component dependency")
    public ResponseEntity<Void> removeComponentDependency(
            @PathVariable UUID componentId, @PathVariable UUID dependencyId) {
        inventory.removeComponentDependency(componentId, dependencyId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/components/used-by")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Every service using a package, any version of it, directly or through its dependencies — "
            + "in each service's own dependency graph — with owners")
    public PackageUsageResponse getPackageUsage(@RequestParam String ecosystem, @RequestParam String name) {
        return usedBy.packageUsage(ecosystem, name);
    }

    @GetMapping("/vendors/{vendorId}/used-by")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Every service using a vendor, with owners")
    public VendorUsageResponse getVendorUsage(@PathVariable UUID vendorId) {
        return usedBy.vendorUsage(vendorId);
    }

    private static ResponseEntity<Void> relationshipCreated(String path) {
        return ResponseEntity.created(uri(path)).build();
    }

    private static URI uri(String path) {
        return URI.create(path);
    }
}
