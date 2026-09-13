package com.aegis.core.inventory.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.InventoryStore.ServiceComponentLink;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** JPA adapter for the inventory domain entities and explicit relationship records. */
@Repository
public class JpaInventoryStore implements InventoryStore {

    private final BusinessServiceJpaRepository services;
    private final SoftwareComponentJpaRepository components;
    private final ThirdPartyVendorJpaRepository vendors;
    private final OwnerJpaRepository owners;
    private final ServiceComponentJpaRepository serviceComponents;
    private final ServiceVendorJpaRepository serviceVendors;
    private final ServiceOwnerJpaRepository serviceOwners;
    private final ComponentDependencyJpaRepository componentDependencies;

    public JpaInventoryStore(
            BusinessServiceJpaRepository services,
            SoftwareComponentJpaRepository components,
            ThirdPartyVendorJpaRepository vendors,
            OwnerJpaRepository owners,
            ServiceComponentJpaRepository serviceComponents,
            ServiceVendorJpaRepository serviceVendors,
            ServiceOwnerJpaRepository serviceOwners,
            ComponentDependencyJpaRepository componentDependencies) {
        this.services = services;
        this.components = components;
        this.vendors = vendors;
        this.owners = owners;
        this.serviceComponents = serviceComponents;
        this.serviceVendors = serviceVendors;
        this.serviceOwners = serviceOwners;
        this.componentDependencies = componentDependencies;
    }

    @Override
    public BusinessService save(BusinessService value) {
        return services.save(value);
    }

    @Override
    public Optional<BusinessService> findBusinessService(UUID id) {
        return services.findById(id);
    }

    @Override
    public Page<BusinessService> findBusinessServices(Pageable pageable) {
        return services.findAll(pageable);
    }

    @Override
    public Optional<BusinessService> findBusinessServiceByName(String name) {
        return services.findByNameIgnoreCase(name);
    }

    @Override
    public boolean businessServiceNameExists(String name, UUID excludingId) {
        return excludingId == null ? services.existsByNameIgnoreCase(name)
                : services.existsByNameIgnoreCaseAndIdNot(name, excludingId);
    }

    @Override
    public void deleteBusinessService(UUID id) {
        services.deleteById(id);
    }

    @Override
    public SoftwareComponent save(SoftwareComponent value) {
        return components.save(value);
    }

    @Override
    public Optional<SoftwareComponent> findSoftwareComponent(UUID id) {
        return components.findById(id);
    }

    @Override
    public Page<SoftwareComponent> findSoftwareComponents(Pageable pageable) {
        return components.findAll(pageable);
    }

    @Override
    public Optional<SoftwareComponent> findSoftwareComponentByCoordinates(String ecosystem, String name, String version) {
        return components.findByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCase(ecosystem, name, version);
    }

    @Override
    public boolean softwareComponentCoordinatesExist(
            String ecosystem, String name, String version, UUID excludingId) {
        return excludingId == null
                ? components.existsByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCase(ecosystem, name, version)
                : components.existsByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCaseAndIdNot(
                        ecosystem, name, version, excludingId);
    }

    @Override
    public void deleteSoftwareComponent(UUID id) {
        components.deleteById(id);
    }

    @Override
    public ThirdPartyVendor save(ThirdPartyVendor value) {
        return vendors.save(value);
    }

    @Override
    public Optional<ThirdPartyVendor> findThirdPartyVendor(UUID id) {
        return vendors.findById(id);
    }

    @Override
    public Page<ThirdPartyVendor> findThirdPartyVendors(Pageable pageable) {
        return vendors.findAll(pageable);
    }

    @Override
    public Optional<ThirdPartyVendor> findThirdPartyVendorByName(String name) {
        return vendors.findByNameIgnoreCase(name);
    }

    @Override
    public boolean thirdPartyVendorNameExists(String name, UUID excludingId) {
        return excludingId == null ? vendors.existsByNameIgnoreCase(name)
                : vendors.existsByNameIgnoreCaseAndIdNot(name, excludingId);
    }

    @Override
    public void deleteThirdPartyVendor(UUID id) {
        vendors.deleteById(id);
    }

    @Override
    public Owner save(Owner value) {
        return owners.save(value);
    }

    @Override
    public Optional<Owner> findOwner(UUID id) {
        return owners.findById(id);
    }

    @Override
    public Page<Owner> findOwners(Pageable pageable) {
        return owners.findAll(pageable);
    }

    @Override
    public Optional<Owner> findOwnerByName(String name) {
        return owners.findByNameIgnoreCase(name);
    }

    @Override
    public boolean ownerNameExists(String name, UUID excludingId) {
        return excludingId == null ? owners.existsByNameIgnoreCase(name)
                : owners.existsByNameIgnoreCaseAndIdNot(name, excludingId);
    }

    @Override
    public void deleteOwner(UUID id) {
        owners.deleteById(id);
    }

    @Override
    public boolean serviceComponentExists(UUID serviceId, UUID componentId) {
        return serviceComponents.existsById(new ServiceComponentEntity.Key(serviceId, componentId));
    }

    @Override
    public void addServiceComponent(UUID serviceId, UUID componentId) {
        serviceComponents.save(new ServiceComponentEntity(serviceId, componentId));
    }

    @Override
    public boolean removeServiceComponent(UUID serviceId, UUID componentId) {
        ServiceComponentEntity.Key key = new ServiceComponentEntity.Key(serviceId, componentId);
        if (!serviceComponents.existsById(key)) {
            return false;
        }
        serviceComponents.deleteById(key);
        return true;
    }

    @Override
    public Page<SoftwareComponent> findServiceComponents(UUID serviceId, Pageable pageable) {
        return components.findForService(serviceId, pageable);
    }

    @Override
    public boolean serviceVendorExists(UUID serviceId, UUID vendorId) {
        return serviceVendors.existsById(new ServiceVendorEntity.Key(serviceId, vendorId));
    }

    @Override
    public void addServiceVendor(UUID serviceId, UUID vendorId) {
        serviceVendors.save(new ServiceVendorEntity(serviceId, vendorId));
    }

    @Override
    public boolean removeServiceVendor(UUID serviceId, UUID vendorId) {
        ServiceVendorEntity.Key key = new ServiceVendorEntity.Key(serviceId, vendorId);
        if (!serviceVendors.existsById(key)) {
            return false;
        }
        serviceVendors.deleteById(key);
        return true;
    }

    @Override
    public Page<ThirdPartyVendor> findServiceVendors(UUID serviceId, Pageable pageable) {
        return vendors.findForService(serviceId, pageable);
    }

    @Override
    public boolean serviceOwnerExists(UUID serviceId, UUID ownerId) {
        return serviceOwners.existsById(new ServiceOwnerEntity.Key(serviceId, ownerId));
    }

    @Override
    public void addServiceOwner(UUID serviceId, UUID ownerId) {
        serviceOwners.save(new ServiceOwnerEntity(serviceId, ownerId));
    }

    @Override
    public boolean removeServiceOwner(UUID serviceId, UUID ownerId) {
        ServiceOwnerEntity.Key key = new ServiceOwnerEntity.Key(serviceId, ownerId);
        if (!serviceOwners.existsById(key)) {
            return false;
        }
        serviceOwners.deleteById(key);
        return true;
    }

    @Override
    public Page<Owner> findServiceOwners(UUID serviceId, Pageable pageable) {
        return owners.findForService(serviceId, pageable);
    }

    @Override
    public boolean componentDependencyExists(UUID componentId, UUID dependencyId) {
        return componentDependencies.existsById(new ComponentDependencyEntity.Key(componentId, dependencyId));
    }

    @Override
    public void addComponentDependency(UUID componentId, UUID dependencyId) {
        componentDependencies.save(new ComponentDependencyEntity(componentId, dependencyId));
    }

    @Override
    public boolean removeComponentDependency(UUID componentId, UUID dependencyId) {
        ComponentDependencyEntity.Key key = new ComponentDependencyEntity.Key(componentId, dependencyId);
        if (!componentDependencies.existsById(key)) {
            return false;
        }
        componentDependencies.deleteById(key);
        return true;
    }

    @Override
    public Page<SoftwareComponent> findComponentDependencies(UUID componentId, Pageable pageable) {
        return components.findDependencies(componentId, pageable);
    }

    @Override
    public Set<UUID> findComponentIdsDependingOn(Collection<UUID> dependencyIds) {
        return componentDependencies.findByDependencyIdIn(dependencyIds).stream()
                .map(ComponentDependencyEntity::getComponentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public List<ServiceComponentLink> findServiceComponentLinks(Collection<UUID> componentIds) {
        return serviceComponents.findByComponentIdIn(componentIds).stream()
                .map(link -> new ServiceComponentLink(link.getBusinessServiceId(), link.getComponentId()))
                .toList();
    }

    @Override
    public Map<UUID, BusinessService> findBusinessServicesByIds(Collection<UUID> ids) {
        return services.findAllById(ids).stream()
                .collect(Collectors.toMap(BusinessService::getId, Function.identity()));
    }

    @Override
    public Map<UUID, SoftwareComponent> findSoftwareComponentsByIds(Collection<UUID> ids) {
        return components.findAllById(ids).stream()
                .collect(Collectors.toMap(SoftwareComponent::getId, Function.identity()));
    }

    @Override
    public Map<UUID, List<Owner>> findOwnersForServices(Collection<UUID> serviceIds) {
        List<ServiceOwnerEntity> links = serviceOwners.findByBusinessServiceIdIn(serviceIds);
        Map<UUID, Owner> ownerById = owners.findAllById(links.stream()
                        .map(ServiceOwnerEntity::getOwnerId)
                        .collect(Collectors.toCollection(LinkedHashSet::new)))
                .stream()
                .collect(Collectors.toMap(Owner::getId, Function.identity()));

        Map<UUID, List<Owner>> ownersByService = new LinkedHashMap<>();
        for (ServiceOwnerEntity link : links) {
            ownersByService.computeIfAbsent(link.getBusinessServiceId(), key -> new ArrayList<>())
                    .add(ownerById.get(link.getOwnerId()));
        }
        return ownersByService;
    }

    @Override
    public List<UUID> findServiceIdsForVendor(UUID vendorId) {
        return serviceVendors.findByVendorId(vendorId).stream()
                .map(ServiceVendorEntity::getBusinessServiceId)
                .toList();
    }
}
