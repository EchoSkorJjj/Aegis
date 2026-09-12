package com.aegis.core.inventory;

import com.aegis.core.inventory.InventoryDtos.BusinessServiceRequest;
import com.aegis.core.inventory.InventoryDtos.BusinessServiceResponse;
import com.aegis.core.inventory.InventoryDtos.NamedInventoryRequest;
import com.aegis.core.inventory.InventoryDtos.OwnerResponse;
import com.aegis.core.inventory.InventoryDtos.SoftwareComponentRequest;
import com.aegis.core.inventory.InventoryDtos.SoftwareComponentResponse;
import com.aegis.core.inventory.InventoryDtos.ThirdPartyVendorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory")
@SecurityRequirement(name = "bearer-jwt")
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @PostMapping("/services")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Create a business service")
    public ResponseEntity<BusinessServiceResponse> createBusinessService(
            @Valid @RequestBody BusinessServiceRequest request) {
        BusinessServiceResponse response = BusinessServiceResponse.from(
                inventory.createBusinessService(request.name(), request.criticality()));
        return ResponseEntity.created(uri("/api/inventory/services/" + response.id())).body(response);
    }

    @GetMapping("/services")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List business services")
    public Page<BusinessServiceResponse> listBusinessServices(@PageableDefault(
            size = 20, sort = {"name", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        return inventory.listBusinessServices(pageable).map(BusinessServiceResponse::from);
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
                inventory.updateBusinessService(id, request.name(), request.criticality()));
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
    @Operation(summary = "List software components")
    public Page<SoftwareComponentResponse> listSoftwareComponents(@PageableDefault(
            size = 20, sort = {"name", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        return inventory.listSoftwareComponents(pageable).map(SoftwareComponentResponse::from);
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
    @Operation(summary = "List third-party vendors")
    public Page<ThirdPartyVendorResponse> listThirdPartyVendors(@PageableDefault(
            size = 20, sort = {"name", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        return inventory.listThirdPartyVendors(pageable).map(ThirdPartyVendorResponse::from);
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

    @PostMapping("/owners")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Create an owner")
    public ResponseEntity<OwnerResponse> createOwner(@Valid @RequestBody NamedInventoryRequest request) {
        OwnerResponse response = OwnerResponse.from(inventory.createOwner(request.name()));
        return ResponseEntity.created(uri("/api/inventory/owners/" + response.id())).body(response);
    }

    @GetMapping("/owners")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List owners")
    public Page<OwnerResponse> listOwners(@PageableDefault(
            size = 20, sort = {"name", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        return inventory.listOwners(pageable).map(OwnerResponse::from);
    }

    @GetMapping("/owners/{id}")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Get an owner")
    public OwnerResponse getOwner(@PathVariable UUID id) {
        return OwnerResponse.from(inventory.getOwner(id));
    }

    @PutMapping("/owners/{id}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Replace an owner")
    public OwnerResponse updateOwner(@PathVariable UUID id, @Valid @RequestBody NamedInventoryRequest request) {
        return OwnerResponse.from(inventory.updateOwner(id, request.name()));
    }

    @DeleteMapping("/owners/{id}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Delete an owner and its relationship edges")
    public ResponseEntity<Void> deleteOwner(@PathVariable UUID id) {
        inventory.deleteOwner(id);
        return ResponseEntity.noContent().build();
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
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Link a component to a business service")
    public ResponseEntity<Void> addServiceComponent(
            @PathVariable UUID serviceId, @PathVariable UUID componentId) {
        inventory.addServiceComponent(serviceId, componentId);
        return relationshipCreated("/api/inventory/services/" + serviceId + "/components/" + componentId);
    }

    @DeleteMapping("/services/{serviceId}/components/{componentId}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Unlink a component from a business service")
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
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Link a vendor to a business service")
    public ResponseEntity<Void> addServiceVendor(@PathVariable UUID serviceId, @PathVariable UUID vendorId) {
        inventory.addServiceVendor(serviceId, vendorId);
        return relationshipCreated("/api/inventory/services/" + serviceId + "/vendors/" + vendorId);
    }

    @DeleteMapping("/services/{serviceId}/vendors/{vendorId}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Unlink a vendor from a business service")
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

    @PostMapping("/services/{serviceId}/owners/{ownerId}")
    @PreAuthorize(InventoryRoles.WRITE)
    @Operation(summary = "Link an owner to a business service")
    public ResponseEntity<Void> addServiceOwner(@PathVariable UUID serviceId, @PathVariable UUID ownerId) {
        inventory.addServiceOwner(serviceId, ownerId);
        return relationshipCreated("/api/inventory/services/" + serviceId + "/owners/" + ownerId);
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

    private static ResponseEntity<Void> relationshipCreated(String path) {
        return ResponseEntity.created(uri(path)).build();
    }

    private static URI uri(String path) {
        return URI.create(path);
    }
}
