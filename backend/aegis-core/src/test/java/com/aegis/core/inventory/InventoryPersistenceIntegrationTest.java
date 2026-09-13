package com.aegis.core.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;

import com.aegis.core.common.ApiExceptions;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.support.AbstractIntegrationTest;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryPersistenceIntegrationTest extends AbstractIntegrationTest {

    private static final PageRequest PAGE = PageRequest.of(0, 20, Sort.by("name", "id"));

    @Autowired
    private InventoryService inventory;

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
        Owner owner = inventory.createOwner("Platform " + suffix);

        for (var value : java.util.List.of(service, component, vendor, owner)) {
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

        assertThat(inventory.listBusinessServices(PAGE).getContent()).contains(service);
        assertThat(inventory.listSoftwareComponents(PAGE).getContent()).contains(component);
        assertThat(inventory.listThirdPartyVendors(PAGE).getContent()).contains(vendor);
        assertThat(inventory.listOwners(PAGE).getContent()).contains(owner);

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
        Owner firstOwner = inventory.createOwner("Orders team " + suffix);
        Owner secondOwner = inventory.createOwner("Risk partner " + suffix);

        inventory.addServiceComponent(service.getId(), app.getId());
        inventory.addServiceVendor(service.getId(), vendor.getId());
        inventory.addServiceOwner(service.getId(), firstOwner.getId());
        inventory.addServiceOwner(service.getId(), secondOwner.getId());
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
        jdbc.update("insert into core.software_components (id, ecosystem, name, version) values (?, ?, ?, ?)",
                componentId, "Maven", "db-check-" + componentId, "1");

        assertThatThrownBy(() -> jdbc.update(
                "insert into core.software_component_dependencies (component_id, dependency_id) values (?, ?)",
                componentId, componentId))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID serviceId = UUID.randomUUID();
        jdbc.update("insert into core.business_services (id, name, criticality) values (?, ?, ?)",
                serviceId, "db-service-" + serviceId, "LOW");

        assertThatThrownBy(() -> jdbc.update(
                "insert into core.business_services (id, name, criticality) values (?, ?, ?)",
                UUID.randomUUID(), ("db-service-" + serviceId).toUpperCase(), "HIGH"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into core.software_components (id, ecosystem, name, version) values (?, ?, ?, ?)",
                UUID.randomUUID(), "MAVEN", ("DB-CHECK-" + componentId).toUpperCase(), "1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID vendorId = UUID.randomUUID();
        String vendorName = "db-vendor-" + vendorId;
        jdbc.update("insert into core.third_party_vendors (id, name) values (?, ?)", vendorId, vendorName);
        assertThatThrownBy(() -> jdbc.update(
                "insert into core.third_party_vendors (id, name) values (?, ?)",
                UUID.randomUUID(), vendorName.toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID ownerId = UUID.randomUUID();
        String ownerName = "db-owner-" + ownerId;
        jdbc.update("insert into core.owners (id, name) values (?, ?)", ownerId, ownerName);
        assertThatThrownBy(() -> jdbc.update(
                "insert into core.owners (id, name) values (?, ?)",
                UUID.randomUUID(), ownerName.toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("insert into core.business_service_components (business_service_id, component_id) values (?, ?)",
                serviceId, componentId);
        assertThatThrownBy(() -> jdbc.update(
                "insert into core.business_service_components (business_service_id, component_id) values (?, ?)",
                serviceId, componentId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                "insert into core.business_services (id, name, criticality) values (?, ?, ?)",
                UUID.randomUUID(), "invalid-criticality-" + serviceId, "EXTREME"))
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

    private void assertJpaEntity(Class<?> type, String tableName) {
        assertThat(type).hasAnnotation(Entity.class).hasAnnotation(Table.class);
        assertThat(type.getAnnotation(Table.class).name()).isEqualTo(tableName);
        assertThat(entityManagerFactory.getMetamodel().entity(type).getJavaType()).isEqualTo(type);
    }
}
