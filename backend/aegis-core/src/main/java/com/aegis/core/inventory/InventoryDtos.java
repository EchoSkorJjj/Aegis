package com.aegis.core.inventory;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record BusinessServiceRequest(
            @NotBlank @Size(max = 255) String name,
            @NotNull BusinessCriticality criticality) {
    }

    public record BusinessServiceResponse(UUID id, String name, BusinessCriticality criticality) {
        static BusinessServiceResponse from(BusinessService value) {
            return new BusinessServiceResponse(value.getId(), value.getName(), value.getCriticality());
        }
    }

    public record SoftwareComponentRequest(
            @NotBlank @Size(max = 64) String ecosystem,
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 128) String version) {
    }

    public record SoftwareComponentResponse(UUID id, String ecosystem, String name, String version) {
        static SoftwareComponentResponse from(SoftwareComponent value) {
            return new SoftwareComponentResponse(
                    value.getId(), value.getEcosystem(), value.getName(), value.getVersion());
        }
    }

    public record NamedInventoryRequest(@NotBlank @Size(max = 255) String name) {
    }

    public record ThirdPartyVendorResponse(UUID id, String name) {
        static ThirdPartyVendorResponse from(ThirdPartyVendor value) {
            return new ThirdPartyVendorResponse(value.getId(), value.getName());
        }
    }

    public record OwnerResponse(UUID id, String name) {
        static OwnerResponse from(Owner value) {
            return new OwnerResponse(value.getId(), value.getName());
        }
    }
}
