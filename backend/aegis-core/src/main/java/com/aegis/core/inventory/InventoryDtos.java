package com.aegis.core.inventory;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

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
}
