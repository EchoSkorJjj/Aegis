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
import com.aegis.core.inventory.domain.SbomImportSource;
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

    /** {@code sbomImportedAt} / {@code sbomImportSource}: when and how its SBOM was last imported, or null. */
    public record BusinessServiceResponse(
            UUID id, String name, BusinessCriticality criticality, ServiceExposure exposure,
            String repositoryUrl, String repositoryBranch,
            Instant sbomImportedAt, SbomImportSource sbomImportSource,
            Instant createdAt, Instant updatedAt) {
        static BusinessServiceResponse from(BusinessService value) {
            return new BusinessServiceResponse(
                    value.getId(), value.getName(), value.getCriticality(), value.getExposure(),
                    value.getRepositoryUrl(), value.getRepositoryBranch(),
                    value.getSbomImportedAt(), value.getSbomImportSource(),
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

    /** A service using something, with the people accountable for it. */
    public record UsingServiceResponse(BusinessServiceResponse service, List<OwnerResponse> owners) {
    }

    /** Every service linking a vendor; vendors are linked directly. */
    public record VendorUsageResponse(ThirdPartyVendorResponse vendor, List<UsingServiceResponse> services) {
    }

    /**
     * One service using one version of a package. {@code direct} when the service links that version itself;
     * otherwise {@code through} is how the service's own dependency graph reaches it, the shortest way: from the
     * component the service links down to the one that depends on this version.
     */
    public record PackageUseResponse(
            BusinessServiceResponse service, List<OwnerResponse> owners, SoftwareComponentResponse component,
            boolean direct, List<SoftwareComponentResponse> through) {
    }

    /** Every service using a package — any version of it — by service, then version. */
    public record PackageUsageResponse(String ecosystem, String name, List<PackageUseResponse> uses) {
    }
}
