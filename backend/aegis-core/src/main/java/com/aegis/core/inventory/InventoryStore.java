package com.aegis.core.inventory;

import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence port used by inventory application logic. */
public interface InventoryStore {

    BusinessService save(BusinessService value);
    Optional<BusinessService> findBusinessService(UUID id);
    Page<BusinessService> findBusinessServices(Pageable pageable);
    boolean businessServiceNameExists(String name, UUID excludingId);
    void deleteBusinessService(UUID id);

    SoftwareComponent save(SoftwareComponent value);
    Optional<SoftwareComponent> findSoftwareComponent(UUID id);
    Page<SoftwareComponent> findSoftwareComponents(Pageable pageable);
    boolean softwareComponentCoordinatesExist(String ecosystem, String name, String version, UUID excludingId);
    void deleteSoftwareComponent(UUID id);

    ThirdPartyVendor save(ThirdPartyVendor value);
    Optional<ThirdPartyVendor> findThirdPartyVendor(UUID id);
    Page<ThirdPartyVendor> findThirdPartyVendors(Pageable pageable);
    boolean thirdPartyVendorNameExists(String name, UUID excludingId);
    void deleteThirdPartyVendor(UUID id);

    Owner save(Owner value);
    Optional<Owner> findOwner(UUID id);
    Page<Owner> findOwners(Pageable pageable);
    boolean ownerNameExists(String name, UUID excludingId);
    void deleteOwner(UUID id);

    boolean serviceComponentExists(UUID serviceId, UUID componentId);
    void addServiceComponent(UUID serviceId, UUID componentId);
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

    boolean componentDependencyExists(UUID componentId, UUID dependencyId);
    void addComponentDependency(UUID componentId, UUID dependencyId);
    boolean removeComponentDependency(UUID componentId, UUID dependencyId);
    Page<SoftwareComponent> findComponentDependencies(UUID componentId, Pageable pageable);

    /** A service's direct edge to a component it uses — the traversal unit for component impact. */
    record ServiceComponentLink(UUID serviceId, UUID componentId) {
    }

    /** Components with a direct dependency edge onto any of {@code dependencyIds} (one BFS level). */
    Set<UUID> findComponentIdsDependingOn(Collection<UUID> dependencyIds);

    /** Every service→component edge for services that use any of {@code componentIds} directly. */
    List<ServiceComponentLink> findServiceComponentLinks(Collection<UUID> componentIds);

    Map<UUID, BusinessService> findBusinessServicesByIds(Collection<UUID> ids);
    Map<UUID, SoftwareComponent> findSoftwareComponentsByIds(Collection<UUID> ids);

    /** Owners of each service in {@code serviceIds}, keyed by service id. */
    Map<UUID, List<Owner>> findOwnersForServices(Collection<UUID> serviceIds);

    List<UUID> findServiceIdsForVendor(UUID vendorId);
}
