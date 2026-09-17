package com.aegis.core.inventory.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import com.aegis.core.inventory.InventoryFilters.ComponentFilter;
import com.aegis.core.inventory.InventoryFilters.NamedFilter;
import com.aegis.core.inventory.InventoryFilters.ServiceFilter;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.InventoryStore.ServiceComponentLink;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.InventoryEntity;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.tenant.CurrentOrg;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/**
 * JPA adapter for the inventory domain, org-scoped (ADR §6). Every method resolves the caller's active
 * org via {@link #scope()} — which also binds it on the DB session for the RLS backstop — then reads
 * only that org's rows and stamps that org onto every write. The application layer (service,
 * controller, port) is unaware of tenancy; isolation lives here and in the DB.
 */
@Repository
public class JpaInventoryStore implements InventoryStore {

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentOrg currentOrg;
    private final BusinessServiceJpaRepository services;
    private final SoftwareComponentJpaRepository components;
    private final ThirdPartyVendorJpaRepository vendors;
    private final OwnerJpaRepository owners;
    private final ServiceComponentJpaRepository serviceComponents;
    private final ServiceVendorJpaRepository serviceVendors;
    private final ServiceOwnerJpaRepository serviceOwners;
    private final ComponentDependencyJpaRepository componentDependencies;

    public JpaInventoryStore(
            CurrentOrg currentOrg,
            BusinessServiceJpaRepository services,
            SoftwareComponentJpaRepository components,
            ThirdPartyVendorJpaRepository vendors,
            OwnerJpaRepository owners,
            ServiceComponentJpaRepository serviceComponents,
            ServiceVendorJpaRepository serviceVendors,
            ServiceOwnerJpaRepository serviceOwners,
            ComponentDependencyJpaRepository componentDependencies) {
        this.currentOrg = currentOrg;
        this.services = services;
        this.components = components;
        this.vendors = vendors;
        this.owners = owners;
        this.serviceComponents = serviceComponents;
        this.serviceVendors = serviceVendors;
        this.serviceOwners = serviceOwners;
        this.componentDependencies = componentDependencies;
    }

    /**
     * The caller's active org (403 if none), also bound as the transaction-local {@code
     * aegis.current_org} GUC so Postgres RLS enforces isolation as a backstop when the app connects as
     * a non-superuser. Always runs inside the calling service's transaction, so the GUC lands on that
     * transaction's connection.
     */
    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString())
                .getSingleResult();
        return org;
    }

    @Override
    public BusinessService save(BusinessService value) {
        UUID org = scope();
        value.assignOrg(org);
        return services.save(value);
    }

    @Override
    public Optional<BusinessService> findBusinessService(UUID id) {
        return services.findByIdAndOrgId(id, scope());
    }

    @Override
    public Page<BusinessService> findBusinessServices(ServiceFilter filter, Pageable pageable) {
        List<Specification<BusinessService>> specs =
                orgAndCreated(scope(), filter.createdAfter(), filter.createdBefore());
        if (hasText(filter.search())) {
            String like = like(filter.search());
            specs.add((root, query, cb) -> cb.like(cb.lower(root.get("name")), like));
        }
        if (filter.criticality() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("criticality"), filter.criticality()));
        }
        return services.findAll(Specification.allOf(specs), pageable);
    }

    @Override
    public Optional<BusinessService> findBusinessServiceByName(String name) {
        return services.findByNameIgnoreCaseAndOrgId(name, scope());
    }

    @Override
    public boolean businessServiceNameExists(String name, UUID excludingId) {
        UUID org = scope();
        return excludingId == null ? services.existsByNameIgnoreCaseAndOrgId(name, org)
                : services.existsByNameIgnoreCaseAndOrgIdAndIdNot(name, org, excludingId);
    }

    @Override
    public void deleteBusinessService(UUID id) {
        scope();
        services.deleteById(id);
    }

    @Override
    public SoftwareComponent save(SoftwareComponent value) {
        UUID org = scope();
        value.assignOrg(org);
        return components.save(value);
    }

    @Override
    public Optional<SoftwareComponent> findSoftwareComponent(UUID id) {
        return components.findByIdAndOrgId(id, scope());
    }

    @Override
    public Page<SoftwareComponent> findSoftwareComponents(ComponentFilter filter, Pageable pageable) {
        UUID org = scope();
        List<Specification<SoftwareComponent>> specs =
                orgAndCreated(org, filter.createdAfter(), filter.createdBefore());
        if (hasText(filter.search())) {
            String like = like(filter.search());
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(root.get("version")), like)));
        }
        if (hasText(filter.ecosystem())) {
            String ecosystem = filter.ecosystem().trim().toLowerCase(Locale.ROOT);
            specs.add((root, query, cb) -> cb.equal(cb.lower(root.get("ecosystem")), ecosystem));
        }
        if (filter.excludeServiceId() != null) {
            specs.add(notLinkedToService(
                    ServiceComponentEntity.class, "componentId", filter.excludeServiceId(), org));
        }
        return components.findAll(Specification.allOf(specs), pageable);
    }

    @Override
    public List<String> findComponentEcosystems() {
        return components.findDistinctEcosystems(scope());
    }

    @Override
    public Optional<SoftwareComponent> findSoftwareComponentByCoordinates(String ecosystem, String name, String version) {
        return components.findByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCaseAndOrgId(
                ecosystem, name, version, scope());
    }

    @Override
    public boolean softwareComponentCoordinatesExist(
            String ecosystem, String name, String version, UUID excludingId) {
        UUID org = scope();
        return excludingId == null
                ? components.existsByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCaseAndOrgId(
                        ecosystem, name, version, org)
                : components.existsByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCaseAndOrgIdAndIdNot(
                        ecosystem, name, version, org, excludingId);
    }

    @Override
    public void deleteSoftwareComponent(UUID id) {
        scope();
        components.deleteById(id);
    }

    @Override
    public ThirdPartyVendor save(ThirdPartyVendor value) {
        UUID org = scope();
        value.assignOrg(org);
        return vendors.save(value);
    }

    @Override
    public Optional<ThirdPartyVendor> findThirdPartyVendor(UUID id) {
        return vendors.findByIdAndOrgId(id, scope());
    }

    @Override
    public Page<ThirdPartyVendor> findThirdPartyVendors(NamedFilter filter, Pageable pageable) {
        UUID org = scope();
        List<Specification<ThirdPartyVendor>> specs = namedSpecs(org, filter);
        if (filter.excludeServiceId() != null) {
            specs.add(notLinkedToService(ServiceVendorEntity.class, "vendorId", filter.excludeServiceId(), org));
        }
        return vendors.findAll(Specification.allOf(specs), pageable);
    }

    @Override
    public Optional<ThirdPartyVendor> findThirdPartyVendorByName(String name) {
        return vendors.findByNameIgnoreCaseAndOrgId(name, scope());
    }

    @Override
    public boolean thirdPartyVendorNameExists(String name, UUID excludingId) {
        UUID org = scope();
        return excludingId == null ? vendors.existsByNameIgnoreCaseAndOrgId(name, org)
                : vendors.existsByNameIgnoreCaseAndOrgIdAndIdNot(name, org, excludingId);
    }

    @Override
    public void deleteThirdPartyVendor(UUID id) {
        scope();
        vendors.deleteById(id);
    }

    @Override
    public Owner save(Owner value) {
        UUID org = scope();
        value.assignOrg(org);
        return owners.save(value);
    }

    @Override
    public Optional<Owner> findOwner(UUID id) {
        return owners.findByIdAndOrgId(id, scope());
    }

    @Override
    public Page<Owner> findOwners(NamedFilter filter, Pageable pageable) {
        UUID org = scope();
        List<Specification<Owner>> specs = namedSpecs(org, filter);
        if (filter.excludeServiceId() != null) {
            specs.add(notLinkedToService(ServiceOwnerEntity.class, "ownerId", filter.excludeServiceId(), org));
        }
        return owners.findAll(Specification.allOf(specs), pageable);
    }

    @Override
    public Optional<Owner> findOwnerByName(String name) {
        return owners.findByNameIgnoreCaseAndOrgId(name, scope());
    }

    @Override
    public boolean ownerNameExists(String name, UUID excludingId) {
        UUID org = scope();
        return excludingId == null ? owners.existsByNameIgnoreCaseAndOrgId(name, org)
                : owners.existsByNameIgnoreCaseAndOrgIdAndIdNot(name, org, excludingId);
    }

    @Override
    public void deleteOwner(UUID id) {
        scope();
        owners.deleteById(id);
    }

    @Override
    public boolean serviceComponentExists(UUID serviceId, UUID componentId) {
        scope();
        return serviceComponents.existsById(new ServiceComponentEntity.Key(serviceId, componentId));
    }

    @Override
    public void addServiceComponent(UUID serviceId, UUID componentId) {
        serviceComponents.save(new ServiceComponentEntity(serviceId, componentId, scope()));
    }

    @Override
    public boolean removeServiceComponent(UUID serviceId, UUID componentId) {
        scope();
        ServiceComponentEntity.Key key = new ServiceComponentEntity.Key(serviceId, componentId);
        if (!serviceComponents.existsById(key)) {
            return false;
        }
        serviceComponents.deleteById(key);
        return true;
    }

    @Override
    public Page<SoftwareComponent> findServiceComponents(UUID serviceId, Pageable pageable) {
        return components.findForService(serviceId, scope(), pageable);
    }

    @Override
    public boolean serviceVendorExists(UUID serviceId, UUID vendorId) {
        scope();
        return serviceVendors.existsById(new ServiceVendorEntity.Key(serviceId, vendorId));
    }

    @Override
    public void addServiceVendor(UUID serviceId, UUID vendorId) {
        serviceVendors.save(new ServiceVendorEntity(serviceId, vendorId, scope()));
    }

    @Override
    public boolean removeServiceVendor(UUID serviceId, UUID vendorId) {
        scope();
        ServiceVendorEntity.Key key = new ServiceVendorEntity.Key(serviceId, vendorId);
        if (!serviceVendors.existsById(key)) {
            return false;
        }
        serviceVendors.deleteById(key);
        return true;
    }

    @Override
    public Page<ThirdPartyVendor> findServiceVendors(UUID serviceId, Pageable pageable) {
        return vendors.findForService(serviceId, scope(), pageable);
    }

    @Override
    public boolean serviceOwnerExists(UUID serviceId, UUID ownerId) {
        scope();
        return serviceOwners.existsById(new ServiceOwnerEntity.Key(serviceId, ownerId));
    }

    @Override
    public void addServiceOwner(UUID serviceId, UUID ownerId) {
        serviceOwners.save(new ServiceOwnerEntity(serviceId, ownerId, scope()));
    }

    @Override
    public boolean removeServiceOwner(UUID serviceId, UUID ownerId) {
        scope();
        ServiceOwnerEntity.Key key = new ServiceOwnerEntity.Key(serviceId, ownerId);
        if (!serviceOwners.existsById(key)) {
            return false;
        }
        serviceOwners.deleteById(key);
        return true;
    }

    @Override
    public Page<Owner> findServiceOwners(UUID serviceId, Pageable pageable) {
        return owners.findForService(serviceId, scope(), pageable);
    }

    @Override
    public boolean componentDependencyExists(UUID componentId, UUID dependencyId) {
        scope();
        return componentDependencies.existsById(new ComponentDependencyEntity.Key(componentId, dependencyId));
    }

    @Override
    public void addComponentDependency(UUID componentId, UUID dependencyId) {
        componentDependencies.save(new ComponentDependencyEntity(componentId, dependencyId, scope()));
    }

    @Override
    public boolean removeComponentDependency(UUID componentId, UUID dependencyId) {
        scope();
        ComponentDependencyEntity.Key key = new ComponentDependencyEntity.Key(componentId, dependencyId);
        if (!componentDependencies.existsById(key)) {
            return false;
        }
        componentDependencies.deleteById(key);
        return true;
    }

    @Override
    public Page<SoftwareComponent> findComponentDependencies(UUID componentId, Pageable pageable) {
        return components.findDependencies(componentId, scope(), pageable);
    }

    @Override
    public Set<UUID> findComponentIdsDependingOn(Collection<UUID> dependencyIds) {
        return componentDependencies.findByDependencyIdInAndOrgId(dependencyIds, scope()).stream()
                .map(ComponentDependencyEntity::getComponentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public List<ServiceComponentLink> findServiceComponentLinks(Collection<UUID> componentIds) {
        return serviceComponents.findByComponentIdInAndOrgId(componentIds, scope()).stream()
                .map(link -> new ServiceComponentLink(link.getBusinessServiceId(), link.getComponentId()))
                .toList();
    }

    @Override
    public Map<UUID, BusinessService> findBusinessServicesByIds(Collection<UUID> ids) {
        return services.findByIdInAndOrgId(ids, scope()).stream()
                .collect(Collectors.toMap(BusinessService::getId, Function.identity()));
    }

    @Override
    public Map<UUID, SoftwareComponent> findSoftwareComponentsByIds(Collection<UUID> ids) {
        return components.findByIdInAndOrgId(ids, scope()).stream()
                .collect(Collectors.toMap(SoftwareComponent::getId, Function.identity()));
    }

    @Override
    public Map<UUID, List<Owner>> findOwnersForServices(Collection<UUID> serviceIds) {
        UUID org = scope();
        List<ServiceOwnerEntity> links = serviceOwners.findByBusinessServiceIdInAndOrgId(serviceIds, org);
        Map<UUID, Owner> ownerById = owners.findByIdInAndOrgId(links.stream()
                        .map(ServiceOwnerEntity::getOwnerId)
                        .collect(Collectors.toCollection(LinkedHashSet::new)), org)
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
        return serviceVendors.findByVendorIdAndOrgId(vendorId, scope()).stream()
                .map(ServiceVendorEntity::getBusinessServiceId)
                .toList();
    }

    // ---- Specification helpers -----------------------------------------------------------------

    /** org = scope AND (optionally) createdAt within [after, before] — the base of every filtered list. */
    private <T extends InventoryEntity> List<Specification<T>> orgAndCreated(
            UUID org, Instant createdAfter, Instant createdBefore) {
        List<Specification<T>> specs = new ArrayList<>();
        specs.add((root, query, cb) -> cb.equal(root.get("orgId"), org));
        if (createdAfter != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), createdAfter));
        }
        if (createdBefore != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), createdBefore));
        }
        return specs;
    }

    /** org + createdAt window + a case-insensitive name substring — for the name-only entities. */
    private <T extends InventoryEntity> List<Specification<T>> namedSpecs(UUID org, NamedFilter filter) {
        List<Specification<T>> specs = orgAndCreated(org, filter.createdAfter(), filter.createdBefore());
        if (hasText(filter.search())) {
            String like = like(filter.search());
            specs.add((root, query, cb) -> cb.like(cb.lower(root.get("name")), like));
        }
        return specs;
    }

    /**
     * Excludes rows already linked to a service:
     * {@code id NOT IN (select <targetIdField> from <joinEntity> where businessServiceId = sid and orgId = org)}.
     * The org clause keeps the exclusion inside the tenant even though the join PK is already org-local.
     */
    private static <T> Specification<T> notLinkedToService(
            Class<?> joinEntity, String targetIdField, UUID serviceId, UUID org) {
        return (root, query, cb) -> {
            Subquery<UUID> linked = query.subquery(UUID.class);
            Root<?> link = linked.from(joinEntity);
            linked.select(link.<UUID>get(targetIdField))
                    .where(cb.equal(link.get("businessServiceId"), serviceId), cb.equal(link.get("orgId"), org));
            return cb.not(root.get("id").in(linked));
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String like(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }
}
