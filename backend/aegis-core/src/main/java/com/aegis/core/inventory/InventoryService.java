package com.aegis.core.inventory;

import java.util.List;
import java.util.UUID;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.identity.DirectoryUser;
import com.aegis.core.platform.identity.IdentityDirectory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final InventoryStore store;
    private final IdentityDirectory directory;

    public InventoryService(InventoryStore store, IdentityDirectory directory) {
        this.store = store;
        this.directory = directory;
    }

    /** {@code exposure} may be null — the service then takes {@link BusinessService#DEFAULT_EXPOSURE}. */
    @Transactional
    public BusinessService createBusinessService(
            String name, BusinessCriticality criticality, ServiceExposure exposure) {
        BusinessService value = new BusinessService(
                UUID.randomUUID(), name, criticality,
                exposure == null ? BusinessService.DEFAULT_EXPOSURE : exposure);
        rejectDuplicateBusinessServiceName(value.getName(), null);
        return store.save(value);
    }

    @Transactional
    public BusinessService createBusinessService(String name, BusinessCriticality criticality) {
        return createBusinessService(name, criticality, null);
    }

    @Transactional(readOnly = true)
    public BusinessService getBusinessService(UUID id) {
        return store.findBusinessService(id)
                .orElseThrow(() -> new ApiExceptions.NotFound("Business service not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<BusinessService> listBusinessServices(InventoryFilters.ServiceFilter filter, Pageable pageable) {
        return store.findBusinessServices(filter, pageable);
    }

    /** {@code exposure} may be null — the service then keeps the exposure it already had. */
    @Transactional
    public BusinessService updateBusinessService(
            UUID id, String name, BusinessCriticality criticality, ServiceExposure exposure) {
        BusinessService value = getBusinessService(id);
        if (exposure == null) {
            value.update(name, criticality);
        } else {
            value.update(name, criticality, exposure);
        }
        rejectDuplicateBusinessServiceName(value.getName(), id);
        return store.save(value);
    }

    @Transactional
    public BusinessService updateBusinessService(UUID id, String name, BusinessCriticality criticality) {
        return updateBusinessService(id, name, criticality, null);
    }

    @Transactional
    public void deleteBusinessService(UUID id) {
        getBusinessService(id);
        store.deleteBusinessService(id);
        // Its owner links go with it (DB cascade); anyone who owned only this service is no longer an owner.
        store.deleteOrphanedOwners();
    }

    @Transactional
    public BusinessService linkServiceRepository(UUID serviceId, RepositoryUrl url, String branch) {
        BusinessService value = getBusinessService(serviceId);
        value.linkRepository(url, branch);
        return store.save(value);
    }

    @Transactional
    public void unlinkServiceRepository(UUID serviceId) {
        BusinessService value = getBusinessService(serviceId);
        value.unlinkRepository();
        store.save(value);
    }

    @Transactional
    public SoftwareComponent createSoftwareComponent(String ecosystem, String name, String version) {
        SoftwareComponent value = new SoftwareComponent(UUID.randomUUID(), ecosystem, name, version);
        rejectDuplicateComponent(value, null);
        return store.save(value);
    }

    @Transactional(readOnly = true)
    public SoftwareComponent getSoftwareComponent(UUID id) {
        return store.findSoftwareComponent(id)
                .orElseThrow(() -> new ApiExceptions.NotFound("Software component not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<SoftwareComponent> listSoftwareComponents(InventoryFilters.ComponentFilter filter, Pageable pageable) {
        return store.findSoftwareComponents(filter, pageable);
    }

    @Transactional(readOnly = true)
    public List<String> listComponentEcosystems() {
        return store.findComponentEcosystems();
    }

    @Transactional
    public SoftwareComponent updateSoftwareComponent(UUID id, String ecosystem, String name, String version) {
        SoftwareComponent value = getSoftwareComponent(id);
        value.update(ecosystem, name, version);
        rejectDuplicateComponent(value, id);
        return store.save(value);
    }

    @Transactional
    public void deleteSoftwareComponent(UUID id) {
        getSoftwareComponent(id);
        store.deleteSoftwareComponent(id);
    }

    @Transactional
    public ThirdPartyVendor createThirdPartyVendor(String name) {
        ThirdPartyVendor value = new ThirdPartyVendor(UUID.randomUUID(), name);
        rejectDuplicateVendorName(value.getName(), null);
        return store.save(value);
    }

    @Transactional(readOnly = true)
    public ThirdPartyVendor getThirdPartyVendor(UUID id) {
        return store.findThirdPartyVendor(id)
                .orElseThrow(() -> new ApiExceptions.NotFound("Third-party vendor not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<ThirdPartyVendor> listThirdPartyVendors(InventoryFilters.NamedFilter filter, Pageable pageable) {
        return store.findThirdPartyVendors(filter, pageable);
    }

    @Transactional
    public ThirdPartyVendor updateThirdPartyVendor(UUID id, String name) {
        ThirdPartyVendor value = getThirdPartyVendor(id);
        value.rename(name);
        rejectDuplicateVendorName(value.getName(), id);
        return store.save(value);
    }

    @Transactional
    public void deleteThirdPartyVendor(UUID id) {
        getThirdPartyVendor(id);
        store.deleteThirdPartyVendor(id);
    }

    @Transactional(readOnly = true)
    public Owner getOwner(UUID id) {
        return store.findOwner(id)
                .orElseThrow(() -> new ApiExceptions.NotFound("Owner not found: " + id));
    }

    /** The people currently accountable for at least one service in the caller's org. */
    @Transactional(readOnly = true)
    public Page<Owner> listOwners(InventoryFilters.NamedFilter filter, Pageable pageable) {
        return store.findOwners(filter, pageable);
    }

    @Transactional
    public void addServiceComponent(UUID serviceId, UUID componentId) {
        getBusinessService(serviceId);
        getSoftwareComponent(componentId);
        if (store.serviceComponentExists(serviceId, componentId)) {
            throw new ApiExceptions.Conflict("Business service already uses that component");
        }
        store.addServiceComponent(serviceId, componentId);
    }

    @Transactional
    public void removeServiceComponent(UUID serviceId, UUID componentId) {
        getBusinessService(serviceId);
        getSoftwareComponent(componentId);
        if (!store.removeServiceComponent(serviceId, componentId)) {
            throw new ApiExceptions.NotFound("Business service component relationship not found");
        }
    }

    @Transactional(readOnly = true)
    public Page<SoftwareComponent> listServiceComponents(UUID serviceId, Pageable pageable) {
        getBusinessService(serviceId);
        return store.findServiceComponents(serviceId, pageable);
    }

    @Transactional
    public void addServiceVendor(UUID serviceId, UUID vendorId) {
        getBusinessService(serviceId);
        getThirdPartyVendor(vendorId);
        if (store.serviceVendorExists(serviceId, vendorId)) {
            throw new ApiExceptions.Conflict("Business service already uses that vendor");
        }
        store.addServiceVendor(serviceId, vendorId);
    }

    @Transactional
    public void removeServiceVendor(UUID serviceId, UUID vendorId) {
        getBusinessService(serviceId);
        getThirdPartyVendor(vendorId);
        if (!store.removeServiceVendor(serviceId, vendorId)) {
            throw new ApiExceptions.NotFound("Business service vendor relationship not found");
        }
    }

    @Transactional(readOnly = true)
    public Page<ThirdPartyVendor> listServiceVendors(UUID serviceId, Pageable pageable) {
        getBusinessService(serviceId);
        return store.findServiceVendors(serviceId, pageable);
    }

    /**
     * Make an organization member one of a service's responsible owners. The member must exist in the
     * caller's active org, be enabled, and hold the {@code SERVICE_OWNER} role — all verified against
     * identity-service with the caller's own token, so a client cannot assign someone outside the org
     * or without the role. There is one owner row per person per org; its name/email snapshot is
     * refreshed on every assignment.
     */
    @Transactional
    public Owner assignServiceOwner(UUID serviceId, UUID userId) {
        getBusinessService(serviceId);
        DirectoryUser member = directory.findMember(userId)
                .orElseThrow(() -> new ApiExceptions.NotFound("No member of this organization with id " + userId));
        if (!member.enabled()) {
            throw new ApiExceptions.BadRequest(member.label() + "'s account is disabled");
        }
        if (!member.hasRole(InventoryRoles.SERVICE_OWNER)) {
            throw new ApiExceptions.BadRequest(member.label() + " does not hold the Service Owner role");
        }
        Owner owner = store.findOwnerByUserId(userId).orElse(null);
        if (owner == null) {
            owner = new Owner(UUID.randomUUID(), userId, member.label(), member.email());
        } else {
            owner.refreshProfile(member.label(), member.email());
        }
        owner = store.save(owner);
        if (store.serviceOwnerExists(serviceId, owner.getId())) {
            throw new ApiExceptions.Conflict(owner.getName() + " already owns this service");
        }
        store.addServiceOwner(serviceId, owner.getId());
        return owner;
    }

    /**
     * Remove an owner from a service. Someone no longer responsible for any service stops being an
     * owner at all, so the owners list is exactly the people currently accountable for something.
     */
    @Transactional
    public void removeServiceOwner(UUID serviceId, UUID ownerId) {
        getBusinessService(serviceId);
        getOwner(ownerId);
        if (!store.removeServiceOwner(serviceId, ownerId)) {
            throw new ApiExceptions.NotFound("Business service owner relationship not found");
        }
        store.deleteOrphanedOwners();
    }

    /**
     * True when {@code userId} is one of the service's assigned owners in the caller's org — the check
     * behind the owner-scoped permission to maintain a service ({@link InventoryRoles#COMPOSE}).
     */
    @Transactional(readOnly = true)
    public boolean isAssignedOwner(UUID serviceId, UUID userId) {
        return store.isServiceOwnedBy(serviceId, userId);
    }

    @Transactional(readOnly = true)
    public Page<Owner> listServiceOwners(UUID serviceId, Pageable pageable) {
        getBusinessService(serviceId);
        return store.findServiceOwners(serviceId, pageable);
    }

    @Transactional
    public void addComponentDependency(UUID componentId, UUID dependencyId) {
        if (componentId.equals(dependencyId)) {
            throw new ApiExceptions.BadRequest("A software component cannot depend on itself");
        }
        getSoftwareComponent(componentId);
        getSoftwareComponent(dependencyId);
        if (store.componentDependencyExists(componentId, dependencyId)) {
            throw new ApiExceptions.Conflict("Software component dependency already exists");
        }
        store.addComponentDependency(componentId, dependencyId);
    }

    @Transactional
    public void removeComponentDependency(UUID componentId, UUID dependencyId) {
        getSoftwareComponent(componentId);
        getSoftwareComponent(dependencyId);
        if (!store.removeComponentDependency(componentId, dependencyId)) {
            throw new ApiExceptions.NotFound("Software component dependency not found");
        }
    }

    @Transactional(readOnly = true)
    public Page<SoftwareComponent> listComponentDependencies(UUID componentId, Pageable pageable) {
        getSoftwareComponent(componentId);
        return store.findComponentDependencies(componentId, pageable);
    }

    private void rejectDuplicateBusinessServiceName(String name, UUID excludingId) {
        if (store.businessServiceNameExists(name, excludingId)) {
            throw new ApiExceptions.Conflict("Business service name already exists: " + name);
        }
    }

    private void rejectDuplicateComponent(SoftwareComponent value, UUID excludingId) {
        if (store.softwareComponentCoordinatesExist(
                value.getEcosystem(), value.getName(), value.getVersion(), excludingId)) {
            throw new ApiExceptions.Conflict("Software component coordinates already exist");
        }
    }

    private void rejectDuplicateVendorName(String name, UUID excludingId) {
        if (store.thirdPartyVendorNameExists(name, excludingId)) {
            throw new ApiExceptions.Conflict("Third-party vendor name already exists: " + name);
        }
    }
}
