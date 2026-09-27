package com.aegis.core.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.aegis.core.inventory.InventoryFilters.ComponentFilter;
import com.aegis.core.inventory.InventoryFilters.NamedFilter;
import com.aegis.core.inventory.InventoryFilters.ServiceFilter;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Persistence port used by inventory application logic. */
public interface InventoryStore {

    BusinessService save(BusinessService value);
    Optional<BusinessService> findBusinessService(UUID id);
    Page<BusinessService> findBusinessServices(ServiceFilter filter, Pageable pageable);
    Optional<BusinessService> findBusinessServiceByName(String name);
    boolean businessServiceNameExists(String name, UUID excludingId);
    void deleteBusinessService(UUID id);

    SoftwareComponent save(SoftwareComponent value);
    Optional<SoftwareComponent> findSoftwareComponent(UUID id);
    Page<SoftwareComponent> findSoftwareComponents(ComponentFilter filter, Pageable pageable);
    Optional<SoftwareComponent> findSoftwareComponentByCoordinates(String ecosystem, String name, String version);
    /** Every version of a package in the caller's org: the components with this ecosystem and name, any case. */
    List<SoftwareComponent> findSoftwareComponentsByPackage(String ecosystem, String name);
    boolean softwareComponentCoordinatesExist(String ecosystem, String name, String version, UUID excludingId);
    void deleteSoftwareComponent(UUID id);
    /** Distinct ecosystems present in the caller's org, sorted — powers the ecosystem filter select. */
    List<String> findComponentEcosystems();

    /** Every component in the caller's org, for deterministic advisory matching. */
    List<SoftwareComponent> findSoftwareComponentsForMatching();

    ThirdPartyVendor save(ThirdPartyVendor value);
    Optional<ThirdPartyVendor> findThirdPartyVendor(UUID id);
    Page<ThirdPartyVendor> findThirdPartyVendors(NamedFilter filter, Pageable pageable);
    Optional<ThirdPartyVendor> findThirdPartyVendorByName(String name);
    boolean thirdPartyVendorNameExists(String name, UUID excludingId);
    void deleteThirdPartyVendor(UUID id);

    /** Every vendor in the caller's org, for deterministic status-incident matching. */
    List<ThirdPartyVendor> findThirdPartyVendorsForMatching();

    Owner save(Owner value);
    Optional<Owner> findOwner(UUID id);
    /** Owners in the caller's org; {@code search} matches the display name or email. */
    Page<Owner> findOwners(NamedFilter filter, Pageable pageable);
    /** The owner row for this identity-service user in the caller's org, if they own anything. */
    Optional<Owner> findOwnerByUserId(UUID userId);
    /** True when this identity-service user is one of the service's assigned owners. */
    boolean isServiceOwnedBy(UUID serviceId, UUID userId);
    /**
     * Drop owners no longer linked to any service, so the owners list is exactly the people currently
     * accountable for something. Flushes pending deletes first (a deleted service's owner links vanish
     * by DB cascade, which only happens once the delete reaches the database).
     */
    void deleteOrphanedOwners();

    boolean serviceComponentExists(UUID serviceId, UUID componentId);
    void addServiceComponent(UUID serviceId, UUID componentId);

    /**
     * Link a component to a service as a direct dependency its repository declares, recording every manifest
     * that declares it; an existing link keeps its manifests unless new ones are given (a non-empty list replaces
     * them). A later import from the repository unlinks it once the repository no longer declares it
     * ({@link #unlinkRepositoryComponentsExcept}), even if it was first linked another way.
     */
    void linkRepositoryComponent(UUID serviceId, UUID componentId, List<String> manifestPaths);

    /**
     * Unlink the service's components that an import from its repository linked, except {@code declared} — what
     * the repository declares now. A component linked by hand or by an uploaded SBOM stays. Returns how many
     * were unlinked.
     */
    int unlinkRepositoryComponentsExcept(UUID serviceId, Collection<UUID> declared);
    boolean removeServiceComponent(UUID serviceId, UUID componentId);
    Page<SoftwareComponent> findServiceComponents(UUID serviceId, Pageable pageable);

    boolean serviceVendorExists(UUID serviceId, UUID vendorId);
    void addServiceVendor(UUID serviceId, UUID vendorId);
    boolean removeServiceVendor(UUID serviceId, UUID vendorId);
    Page<ThirdPartyVendor> findServiceVendors(UUID serviceId, Pageable pageable);

    boolean serviceOwnerExists(UUID serviceId, UUID ownerId);
    void addServiceOwner(UUID serviceId, UUID ownerId);
    boolean removeServiceOwner(UUID serviceId, UUID ownerId);
    Page<Owner> findServiceOwners(UUID serviceId, Pageable pageable);

    /** An edge added by hand ({@link ComponentDependencyLink#serviceId()} null), which holds in every service. */
    boolean componentDependencyExists(UUID componentId, UUID dependencyId);
    void addComponentDependency(UUID componentId, UUID dependencyId);
    boolean removeComponentDependency(UUID componentId, UUID dependencyId);
    /** What the component depends on anywhere in the org: edges added by hand, and every service's own. */
    Page<SoftwareComponent> findComponentDependencies(UUID componentId, Pageable pageable);

    /**
     * Record the dependency edges one import of a service's SBOM declares, as that service's own (each declared
     * edge's {@code serviceId} is ignored). An import from the service's repository ({@code fromRepository})
     * also removes the edges an earlier one recorded that it no longer declares, and adopts the rest; an upload
     * only adds.
     */
    DependencyChanges recordServiceDependencies(
            UUID serviceId, Collection<ComponentDependencyLink> declared, boolean fromRepository);

    /** What {@link #recordServiceDependencies} changed. */
    record DependencyChanges(int added, int removed) {
    }

    /**
     * A service's direct edge to a component it uses — the traversal unit for component impact.
     * {@code manifestPaths} are the repository files declaring it, where known (empty otherwise).
     */
    record ServiceComponentLink(UUID serviceId, UUID componentId, List<String> manifestPaths) {

        public ServiceComponentLink {
            manifestPaths = manifestPaths == null ? List.of() : List.copyOf(manifestPaths);
        }

        public ServiceComponentLink(UUID serviceId, UUID componentId) {
            this(serviceId, componentId, List.of());
        }
    }

    /**
     * A direct dependency edge: {@code componentId} depends on {@code dependencyId}. {@code serviceId} is the service
     * whose SBOM declared it — how that service resolved it, which another service's lockfile may resolve
     * differently — or null for an edge added by hand, which holds in every service.
     */
    record ComponentDependencyLink(UUID componentId, UUID dependencyId, UUID serviceId) {

        /** An edge added by hand: it holds in every service. */
        public ComponentDependencyLink(UUID componentId, UUID dependencyId) {
            this(componentId, dependencyId, null);
        }
    }

    /** Reverse edges, one level: those into any of {@code dependencyIds} — added by hand, and every service's own. */
    List<ComponentDependencyLink> findComponentDependencyLinks(Collection<UUID> dependencyIds);

    /** Every service→component edge for services that use any of {@code componentIds} directly. */
    List<ServiceComponentLink> findServiceComponentLinks(Collection<UUID> componentIds);

    /** Every direct component edge of the given services. */
    List<ServiceComponentLink> findServiceComponentLinksForServices(Collection<UUID> serviceIds);

    /**
     * Forward edges, one level: what each of {@code componentIds} depends on directly — edges added by hand, and
     * those {@code serviceIds} declared.
     */
    List<ComponentDependencyLink> findDependenciesOf(Collection<UUID> componentIds, Collection<UUID> serviceIds);

    Map<UUID, BusinessService> findBusinessServicesByIds(Collection<UUID> ids);
    Map<UUID, SoftwareComponent> findSoftwareComponentsByIds(Collection<UUID> ids);

    /** Owners of each service in {@code serviceIds}, keyed by service id. */
    Map<UUID, List<Owner>> findOwnersForServices(Collection<UUID> serviceIds);

    List<UUID> findServiceIdsForVendor(UUID vendorId);
}
