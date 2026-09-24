package com.aegis.core.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.TestOwners;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryPersistenceIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final PageRequest PAGE = PageRequest.of(0, 20, Sort.by("name", "id"));

    /** Org for the raw-JDBC constraint checks, which bypass the store and so must supply org_id (ADR §6). */
    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private InventoryService inventory;

    @Autowired
    private TestOwners owners;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void storyOneDomainTypesAreTheRegisteredJpaEntities() {
        assertJpaEntity(BusinessService.class, "business_services");
        assertJpaEntity(SoftwareComponent.class, "software_components");
        assertJpaEntity(ThirdPartyVendor.class, "third_party_vendors");
        assertJpaEntity(Owner.class, "owners");
    }

    @Test
    void persistsAndMapsEveryInventoryEntity() {
        String suffix = UUID.randomUUID().toString();

        BusinessService service = inventory.createBusinessService("Payments " + suffix, BusinessCriticality.CRITICAL);
        SoftwareComponent component = inventory.createSoftwareComponent("Maven", "spring-core-" + suffix, "7.0.0");
        ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Acme " + suffix);
        Owner owner = owners.assign(service.getId(), "Platform " + suffix);

        for (var value : List.of(service, component, vendor, owner)) {
            assertThat(value.getId()).isNotNull();
            assertThat(value.getCreatedAt()).isNotNull();
            assertThat(value.getUpdatedAt()).isNotNull();
        }

        BusinessService reloadedService = inventory.getBusinessService(service.getId());
        assertThat(reloadedService).isEqualTo(service).isNotSameAs(service);
        assertThat(reloadedService.getCriticality()).isEqualTo(BusinessCriticality.CRITICAL);
        assertThat(inventory.getSoftwareComponent(component.getId()))
                .isEqualTo(component)
                .extracting(SoftwareComponent::getVersion).isEqualTo("7.0.0");
        assertThat(inventory.getThirdPartyVendor(vendor.getId())).isEqualTo(vendor);
        assertThat(inventory.getOwner(owner.getId())).isEqualTo(owner);

        assertThat(jdbc.queryForObject(
                "select criticality from core.business_services where id = ?",
                String.class, service.getId())).isEqualTo("CRITICAL");

        // Other test classes share this database, so the new rows may not be on the first page.
        assertThat(allPages(p -> inventory.listBusinessServices(InventoryFilters.ServiceFilter.NONE, p)))
                .contains(service);
        assertThat(allPages(p -> inventory.listSoftwareComponents(InventoryFilters.ComponentFilter.NONE, p)))
                .contains(component);
        assertThat(allPages(p -> inventory.listThirdPartyVendors(InventoryFilters.NamedFilter.NONE, p)))
                .contains(vendor);
        assertThat(allPages(p -> inventory.listOwners(InventoryFilters.NamedFilter.NONE, p)))
                .contains(owner);

        Instant previousUpdatedAt = service.getUpdatedAt();
        BusinessService updated = inventory.updateBusinessService(
                service.getId(), "Checkout " + suffix, BusinessCriticality.HIGH);
        assertThat(updated.getName()).startsWith("Checkout");
        assertThat(updated.getCriticality()).isEqualTo(BusinessCriticality.HIGH);
        assertThat(updated.getCreatedAt()).isEqualTo(service.getCreatedAt());
        assertThat(updated.getUpdatedAt()).isAfter(previousUpdatedAt);

        BusinessService reference = transactions.execute(
                status -> entityManager.getReference(BusinessService.class, service.getId()));
        assertThat(Hibernate.isInitialized(reference)).isFalse();
        assertThat(service).isEqualTo(reference).hasSameHashCodeAs(reference);
        assertThat(reference).isEqualTo(service);
        assertThat(Hibernate.isInitialized(reference)).isFalse();
    }

    @Test
    void persistsServiceRelationshipsMultipleOwnersAndDirectDependencies() {
        String suffix = UUID.randomUUID().toString();
        BusinessService service = inventory.createBusinessService("Orders " + suffix, BusinessCriticality.HIGH);
        SoftwareComponent app = inventory.createSoftwareComponent("npm", "orders-" + suffix, "1.0.0");
        SoftwareComponent library = inventory.createSoftwareComponent("npm", "library-" + suffix, "2.0.0");
        ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Cloud " + suffix);
        inventory.addServiceComponent(service.getId(), app.getId());
        inventory.addServiceVendor(service.getId(), vendor.getId());
        Owner firstOwner = owners.assign(service.getId(), "Orders team " + suffix);
        Owner secondOwner = owners.assign(service.getId(), "Risk partner " + suffix);
        inventory.addComponentDependency(app.getId(), library.getId());

        assertThat(inventory.listServiceComponents(service.getId(), PAGE).getContent()).containsExactly(app);
        assertThat(inventory.listServiceVendors(service.getId(), PAGE).getContent()).containsExactly(vendor);
        assertThat(inventory.listServiceOwners(service.getId(), PAGE).getContent())
                .containsExactlyInAnyOrder(firstOwner, secondOwner);
        assertThat(inventory.listComponentDependencies(app.getId(), PAGE).getContent()).containsExactly(library);
    }

    @Test
    void rejectsDuplicateEdgesAndDirectSelfDependency() {
        String suffix = UUID.randomUUID().toString();
        BusinessService service = inventory.createBusinessService("Ledger " + suffix, BusinessCriticality.MEDIUM);
        SoftwareComponent component = inventory.createSoftwareComponent("Maven", "ledger-" + suffix, "1");

        inventory.addServiceComponent(service.getId(), component.getId());

        assertThatThrownBy(() -> inventory.addServiceComponent(service.getId(), component.getId()))
                .isInstanceOf(ApiExceptions.Conflict.class);
        assertThatThrownBy(() -> inventory.addComponentDependency(component.getId(), component.getId()))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("cannot depend on itself");
    }

    @Test
    void databaseConstraintsRejectInvalidRowsAndDuplicateEdges() {
        UUID componentId = UUID.randomUUID();
        jdbc.update("insert into core.software_components (id, ecosystem, name, version, org_id) values (?, ?, ?, ?, ?)",
                componentId, "Maven", "db-check-" + componentId, "1", ORG);

        assertThatThrownBy(() -> jdbc.update(
                "insert into core.software_component_dependencies (component_id, dependency_id, org_id) values (?, ?, ?)",
                componentId, componentId, ORG))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID serviceId = UUID.randomUUID();
        jdbc.update("insert into core.business_services (id, name, criticality, org_id) values (?, ?, ?, ?)",
                serviceId, "db-service-" + serviceId, "LOW", ORG);

        assertThatThrownBy(() -> jdbc.update(
                "insert into core.business_services (id, name, criticality, org_id) values (?, ?, ?, ?)",
                UUID.randomUUID(), ("db-service-" + serviceId).toUpperCase(), "HIGH", ORG))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into core.software_components (id, ecosystem, name, version, org_id) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), "MAVEN", ("DB-CHECK-" + componentId).toUpperCase(), "1", ORG))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID vendorId = UUID.randomUUID();
        String vendorName = "db-vendor-" + vendorId;
        jdbc.update("insert into core.third_party_vendors (id, name, org_id) values (?, ?, ?)",
                vendorId, vendorName, ORG);
        assertThatThrownBy(() -> jdbc.update(
                "insert into core.third_party_vendors (id, name, org_id) values (?, ?, ?)",
                UUID.randomUUID(), vendorName.toUpperCase(), ORG))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Owners are unique per person (identity user id) per org — not by name (V16): one person can't
        // be two owners, but two people may share a display name.
        UUID ownerUserId = UUID.randomUUID();
        String ownerName = "db-owner-" + ownerUserId;
        jdbc.update("insert into core.owners (id, user_id, name, org_id) values (?, ?, ?, ?)",
                UUID.randomUUID(), ownerUserId, ownerName, ORG);
        assertThatThrownBy(() -> jdbc.update(
                "insert into core.owners (id, user_id, name, org_id) values (?, ?, ?, ?)",
                UUID.randomUUID(), ownerUserId, "someone else", ORG))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into core.owners (id, user_id, name, org_id) values (?, ?, ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), ownerName, ORG);
        assertThatThrownBy(() -> jdbc.update(
                "insert into core.owners (id, name, org_id) values (?, ?, ?)",
                UUID.randomUUID(), "no-user-" + ownerUserId, ORG))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("insert into core.business_service_components (business_service_id, component_id, org_id)"
                        + " values (?, ?, ?)", serviceId, componentId, ORG);
        assertThatThrownBy(() -> jdbc.update(
                "insert into core.business_service_components (business_service_id, component_id, org_id)"
                        + " values (?, ?, ?)", serviceId, componentId, ORG))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                "insert into core.business_services (id, name, criticality, org_id) values (?, ?, ?, ?)",
                UUID.randomUUID(), "invalid-criticality-" + serviceId, "EXTREME", ORG))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAnEntityCascadesOnlyItsEdges() {
        String suffix = UUID.randomUUID().toString();
        BusinessService service = inventory.createBusinessService("Delete me " + suffix, BusinessCriticality.LOW);
        SoftwareComponent component = inventory.createSoftwareComponent("Go", "kept-" + suffix, "1.22");
        inventory.addServiceComponent(service.getId(), component.getId());

        inventory.deleteBusinessService(service.getId());

        assertThat(inventory.getSoftwareComponent(component.getId())).isEqualTo(component);
        Integer edges = jdbc.queryForObject(
                "select count(*) from core.business_service_components where business_service_id = ?",
                Integer.class, service.getId());
        assertThat(edges).isZero();
        assertThatThrownBy(() -> inventory.getBusinessService(service.getId()))
                .isInstanceOf(ApiExceptions.NotFound.class);
    }

    private static <T> List<T> allPages(Function<Pageable, Page<T>> query) {
        List<T> all = new ArrayList<>();
        Pageable pageable = PAGE;
        Page<T> page;
        do {
            page = query.apply(pageable);
            all.addAll(page.getContent());
            pageable = page.nextPageable();
        } while (page.hasNext());
        return all;
    }

    private void assertJpaEntity(Class<?> type, String tableName) {
        assertThat(type).hasAnnotation(Entity.class).hasAnnotation(Table.class);
        assertThat(type.getAnnotation(Table.class).name()).isEqualTo(tableName);
        assertThat(entityManagerFactory.getMetamodel().entity(type).getJavaType()).isEqualTo(type);
    }
}
