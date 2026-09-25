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
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    /**
     * {@code exposure} is optional so a client written before the attribute existed keeps working; it
     * then falls back to {@link BusinessService#DEFAULT_EXPOSURE}. On an update, omitting it keeps the
     * service's current exposure rather than resetting it — a PUT that does not mention the attribute
     * must not silently downgrade a service declared internet-facing.
     */
    public record BusinessServiceRequest(
            @NotBlank @Size(max = 255) String name,
            @NotNull BusinessCriticality criticality,
            ServiceExposure exposure) {
    }

    public record BusinessServiceResponse(
            UUID id, String name, BusinessCriticality criticality, ServiceExposure exposure,
            String repositoryUrl, String repositoryBranch,
            Instant createdAt, Instant updatedAt) {
        static BusinessServiceResponse from(BusinessService value) {
            return new BusinessServiceResponse(
                    value.getId(), value.getName(), value.getCriticality(), value.getExposure(),
                    value.getRepositoryUrl(), value.getRepositoryBranch(),
                    value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    public record ServiceRepositoryRequest(
            @NotBlank @Size(max = 400) String url,
            @Size(max = 200) String branch) {
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

    /**
     * An owner: an organization member holding {@code SERVICE_OWNER}. {@code userId} is their
     * identity-service id; {@code name} and {@code email} are the snapshot taken when last assigned.
     */
    public record OwnerResponse(
            UUID id, UUID userId, String name, String email, Instant createdAt, Instant updatedAt) {
        static OwnerResponse from(Owner value) {
            return new OwnerResponse(
                    value.getId(), value.getUserId(), value.getName(), value.getEmail(),
                    value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    /** Assign an organization member (by identity-service user id) as one of a service's owners. */
    public record AssignOwnerRequest(@NotNull UUID userId) {
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
