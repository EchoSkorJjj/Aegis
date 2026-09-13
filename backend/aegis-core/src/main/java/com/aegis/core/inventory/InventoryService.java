package com.aegis.core.inventory;

import com.aegis.core.common.ApiExceptions;
import com.aegis.core.inventory.InventoryDtos.BusinessServiceResponse;
import com.aegis.core.inventory.InventoryDtos.ComponentImpactResponse;
import com.aegis.core.inventory.InventoryDtos.ComponentImpactedServiceResponse;
import com.aegis.core.inventory.InventoryDtos.ImpactedServiceResponse;
import com.aegis.core.inventory.InventoryDtos.OwnerResponse;
import com.aegis.core.inventory.InventoryDtos.SoftwareComponentResponse;
import com.aegis.core.inventory.InventoryDtos.ThirdPartyVendorResponse;
import com.aegis.core.inventory.InventoryDtos.VendorImpactResponse;
import com.aegis.core.inventory.InventoryStore.ServiceComponentLink;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    private final InventoryStore store;

    public InventoryService(InventoryStore store) {
        this.store = store;
    }

    @Transactional
    public BusinessService createBusinessService(String name, BusinessCriticality criticality) {
        BusinessService value = new BusinessService(UUID.randomUUID(), name, criticality);
        rejectDuplicateBusinessServiceName(value.getName(), null);
        return store.save(value);
    }

    @Transactional(readOnly = true)
    public BusinessService getBusinessService(UUID id) {
        return store.findBusinessService(id)
                .orElseThrow(() -> new ApiExceptions.NotFound("Business service not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<BusinessService> listBusinessServices(Pageable pageable) {
        return store.findBusinessServices(pageable);
    }

    @Transactional
    public BusinessService updateBusinessService(UUID id, String name, BusinessCriticality criticality) {
        BusinessService value = getBusinessService(id);
        value.update(name, criticality);
        rejectDuplicateBusinessServiceName(value.getName(), id);
        return store.save(value);
    }

    @Transactional
    public void deleteBusinessService(UUID id) {
        getBusinessService(id);
        store.deleteBusinessService(id);
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
    public Page<SoftwareComponent> listSoftwareComponents(Pageable pageable) {
        return store.findSoftwareComponents(pageable);
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
    public Page<ThirdPartyVendor> listThirdPartyVendors(Pageable pageable) {
        return store.findThirdPartyVendors(pageable);
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

    @Transactional
    public Owner createOwner(String name) {
        Owner value = new Owner(UUID.randomUUID(), name);
        rejectDuplicateOwnerName(value.getName(), null);
        return store.save(value);
    }

    @Transactional(readOnly = true)
    public Owner getOwner(UUID id) {
        return store.findOwner(id)
                .orElseThrow(() -> new ApiExceptions.NotFound("Owner not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<Owner> listOwners(Pageable pageable) {
        return store.findOwners(pageable);
    }

    @Transactional
    public Owner updateOwner(UUID id, String name) {
        Owner value = getOwner(id);
        value.rename(name);
        rejectDuplicateOwnerName(value.getName(), id);
        return store.save(value);
    }

    @Transactional
    public void deleteOwner(UUID id) {
        getOwner(id);
        store.deleteOwner(id);
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

    @Transactional
    public void addServiceOwner(UUID serviceId, UUID ownerId) {
        getBusinessService(serviceId);
        getOwner(ownerId);
        if (store.serviceOwnerExists(serviceId, ownerId)) {
            throw new ApiExceptions.Conflict("Business service already has that owner");
        }
        store.addServiceOwner(serviceId, ownerId);
    }

    @Transactional
    public void removeServiceOwner(UUID serviceId, UUID ownerId) {
        getBusinessService(serviceId);
        getOwner(ownerId);
        if (!store.removeServiceOwner(serviceId, ownerId)) {
            throw new ApiExceptions.NotFound("Business service owner relationship not found");
        }
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

    /**
     * Services (and their owners) impacted by a component: those using it directly, plus those
     * using some other component that depends on it, directly or transitively.
     */
    @Transactional(readOnly = true)
    public ComponentImpactResponse getComponentImpact(UUID componentId) {
        SoftwareComponent target = getSoftwareComponent(componentId);
        Set<UUID> closure = resolveDependentClosure(componentId);
        List<ServiceComponentLink> links = store.findServiceComponentLinks(closure);
        if (links.isEmpty()) {
            return new ComponentImpactResponse(SoftwareComponentResponse.from(target), List.of());
        }

        Map<UUID, List<ServiceComponentLink>> linksByService = new LinkedHashMap<>();
        for (ServiceComponentLink link : links) {
            linksByService.computeIfAbsent(link.serviceId(), key -> new ArrayList<>()).add(link);
        }

        Map<UUID, BusinessService> servicesById = store.findBusinessServicesByIds(linksByService.keySet());
        Map<UUID, SoftwareComponent> componentsById = store.findSoftwareComponentsByIds(
                links.stream().map(ServiceComponentLink::componentId).collect(Collectors.toSet()));
        Map<UUID, List<Owner>> ownersByService = store.findOwnersForServices(linksByService.keySet());

        List<ComponentImpactedServiceResponse> services = new ArrayList<>();
        for (Map.Entry<UUID, List<ServiceComponentLink>> entry : linksByService.entrySet()) {
            ServiceComponentLink chosen = entry.getValue().stream()
                    .filter(link -> link.componentId().equals(componentId))
                    .findFirst()
                    .orElseGet(() -> entry.getValue().get(0));
            services.add(new ComponentImpactedServiceResponse(
                    BusinessServiceResponse.from(servicesById.get(entry.getKey())),
                    toOwnerResponses(ownersByService.get(entry.getKey())),
                    chosen.componentId().equals(componentId),
                    SoftwareComponentResponse.from(componentsById.get(chosen.componentId()))));
        }
        services.sort(Comparator.comparing(response -> response.service().name(), String.CASE_INSENSITIVE_ORDER));

        return new ComponentImpactResponse(SoftwareComponentResponse.from(target), services);
    }

    /** Services (and their owners) impacted by a vendor: those using it directly. */
    @Transactional(readOnly = true)
    public VendorImpactResponse getVendorImpact(UUID vendorId) {
        ThirdPartyVendor vendor = getThirdPartyVendor(vendorId);
        List<UUID> serviceIds = store.findServiceIdsForVendor(vendorId);
        if (serviceIds.isEmpty()) {
            return new VendorImpactResponse(ThirdPartyVendorResponse.from(vendor), List.of());
        }

        Map<UUID, BusinessService> servicesById = store.findBusinessServicesByIds(serviceIds);
        Map<UUID, List<Owner>> ownersByService = store.findOwnersForServices(serviceIds);

        List<ImpactedServiceResponse> services = serviceIds.stream()
                .map(serviceId -> new ImpactedServiceResponse(
                        BusinessServiceResponse.from(servicesById.get(serviceId)),
                        toOwnerResponses(ownersByService.get(serviceId))))
                .sorted(Comparator.comparing(response -> response.service().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new VendorImpactResponse(ThirdPartyVendorResponse.from(vendor), services);
    }

    /** BFS over dependency edges: the component itself plus every component that depends on it. */
    private Set<UUID> resolveDependentClosure(UUID componentId) {
        Set<UUID> closure = new LinkedHashSet<>();
        closure.add(componentId);
        Set<UUID> frontier = Set.of(componentId);
        while (!frontier.isEmpty()) {
            Set<UUID> next = new LinkedHashSet<>();
            for (UUID dependent : store.findComponentIdsDependingOn(frontier)) {
                if (closure.add(dependent)) {
                    next.add(dependent);
                }
            }
            frontier = next;
        }
        return closure;
    }

    private static List<OwnerResponse> toOwnerResponses(List<Owner> owners) {
        return owners == null ? List.of() : owners.stream().map(OwnerResponse::from).toList();
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

    private void rejectDuplicateOwnerName(String name, UUID excludingId) {
        if (store.ownerNameExists(name, excludingId)) {
            throw new ApiExceptions.Conflict("Owner name already exists: " + name);
        }
    }
}
