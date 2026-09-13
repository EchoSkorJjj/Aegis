package com.aegis.core.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record BusinessServiceRequest(
            @NotBlank @Size(max = 255) String name,
            @NotNull BusinessCriticality criticality) {
    }

    public record BusinessServiceResponse(
            UUID id, String name, BusinessCriticality criticality, Instant createdAt, Instant updatedAt) {
        static BusinessServiceResponse from(BusinessService value) {
            return new BusinessServiceResponse(
                    value.getId(), value.getName(), value.getCriticality(),
                    value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    public record SoftwareComponentRequest(
            @NotBlank @Size(max = 64) String ecosystem,
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 128) String version) {
    }

    public record SoftwareComponentResponse(
            UUID id, String ecosystem, String name, String version, Instant createdAt, Instant updatedAt) {
        static SoftwareComponentResponse from(SoftwareComponent value) {
            return new SoftwareComponentResponse(
                    value.getId(), value.getEcosystem(), value.getName(), value.getVersion(),
                    value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    public record NamedInventoryRequest(@NotBlank @Size(max = 255) String name) {
    }

    public record ThirdPartyVendorResponse(UUID id, String name, Instant createdAt, Instant updatedAt) {
        static ThirdPartyVendorResponse from(ThirdPartyVendor value) {
            return new ThirdPartyVendorResponse(
                    value.getId(), value.getName(), value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    public record OwnerResponse(UUID id, String name, Instant createdAt, Instant updatedAt) {
        static OwnerResponse from(Owner value) {
            return new OwnerResponse(
                    value.getId(), value.getName(), value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    /** A service impacted through a direct relationship only (e.g. via a vendor). */
    public record ImpactedServiceResponse(BusinessServiceResponse service, List<OwnerResponse> owners) {
    }

    /**
     * A service impacted by a component, direct or transitive. {@code viaComponent} is the
     * component the service actually depends on directly; {@code direct} is true only when that
     * is the queried component itself, false when it was reached through a dependency chain.
     */
    public record ComponentImpactedServiceResponse(
            BusinessServiceResponse service, List<OwnerResponse> owners, boolean direct,
            SoftwareComponentResponse viaComponent) {
    }

    public record ComponentImpactResponse(
            SoftwareComponentResponse component, List<ComponentImpactedServiceResponse> services) {
    }

    public record VendorImpactResponse(
            ThirdPartyVendorResponse vendor, List<ImpactedServiceResponse> services) {
    }
}
