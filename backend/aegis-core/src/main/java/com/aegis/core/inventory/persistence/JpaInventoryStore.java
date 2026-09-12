package com.aegis.core.inventory.persistence;

import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** JPA adapter; persistence entities never cross the inventory module boundary. */
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
        BusinessServiceEntity entity = services.findById(value.getId()).orElse(null);
        if (entity == null) {
            entity = new BusinessServiceEntity(value.getId(), value.getName(), value.getCriticality());
        } else {
            entity.update(value.getName(), value.getCriticality());
        }
        return toDomain(services.save(entity));
    }

    @Override
    public Optional<BusinessService> findBusinessService(UUID id) {
        return services.findById(id).map(JpaInventoryStore::toDomain);
    }

    @Override
    public Page<BusinessService> findBusinessServices(Pageable pageable) {
        return services.findAll(pageable).map(JpaInventoryStore::toDomain);
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
        SoftwareComponentEntity entity = components.findById(value.getId()).orElse(null);
        if (entity == null) {
            entity = new SoftwareComponentEntity(value.getId(), value.getEcosystem(), value.getName(), value.getVersion());
        } else {
            entity.update(value.getEcosystem(), value.getName(), value.getVersion());
        }
        return toDomain(components.save(entity));
    }

    @Override
    public Optional<SoftwareComponent> findSoftwareComponent(UUID id) {
        return components.findById(id).map(JpaInventoryStore::toDomain);
    }

    @Override
    public Page<SoftwareComponent> findSoftwareComponents(Pageable pageable) {
        return components.findAll(pageable).map(JpaInventoryStore::toDomain);
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
        ThirdPartyVendorEntity entity = vendors.findById(value.getId()).orElse(null);
        if (entity == null) {
            entity = new ThirdPartyVendorEntity(value.getId(), value.getName());
        } else {
            entity.update(value.getName());
        }
        return toDomain(vendors.save(entity));
    }

    @Override
    public Optional<ThirdPartyVendor> findThirdPartyVendor(UUID id) {
        return vendors.findById(id).map(JpaInventoryStore::toDomain);
    }

    @Override
    public Page<ThirdPartyVendor> findThirdPartyVendors(Pageable pageable) {
        return vendors.findAll(pageable).map(JpaInventoryStore::toDomain);
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
        OwnerEntity entity = owners.findById(value.getId()).orElse(null);
        if (entity == null) {
            entity = new OwnerEntity(value.getId(), value.getName());
        } else {
            entity.update(value.getName());
        }
        return toDomain(owners.save(entity));
    }

    @Override
    public Optional<Owner> findOwner(UUID id) {
        return owners.findById(id).map(JpaInventoryStore::toDomain);
    }

    @Override
    public Page<Owner> findOwners(Pageable pageable) {
        return owners.findAll(pageable).map(JpaInventoryStore::toDomain);
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
        return components.findForService(serviceId, pageable).map(JpaInventoryStore::toDomain);
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
        return vendors.findForService(serviceId, pageable).map(JpaInventoryStore::toDomain);
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
        return owners.findForService(serviceId, pageable).map(JpaInventoryStore::toDomain);
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
        return components.findDependencies(componentId, pageable).map(JpaInventoryStore::toDomain);
    }

    private static BusinessService toDomain(BusinessServiceEntity entity) {
        return new BusinessService(entity.id(), entity.name(), entity.criticality());
    }

    private static SoftwareComponent toDomain(SoftwareComponentEntity entity) {
        return new SoftwareComponent(entity.id(), entity.ecosystem(), entity.name(), entity.version());
    }

    private static ThirdPartyVendor toDomain(ThirdPartyVendorEntity entity) {
        return new ThirdPartyVendor(entity.id(), entity.name());
    }

    private static Owner toDomain(OwnerEntity entity) {
        return new Owner(entity.id(), entity.name());
    }
}
