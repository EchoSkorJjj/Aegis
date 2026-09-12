package com.aegis.core.inventory;

import com.aegis.core.common.ApiExceptions;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryPersistenceIntegrationTest extends AbstractIntegrationTest {

    private static final PageRequest PAGE = PageRequest.of(0, 20, Sort.by("name", "id"));

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void persistsAndMapsEveryInventoryEntity() {
        String suffix = UUID.randomUUID().toString();

        BusinessService service = inventory.createBusinessService("Payments " + suffix, BusinessCriticality.CRITICAL);
        SoftwareComponent component = inventory.createSoftwareComponent("Maven", "spring-core-" + suffix, "7.0.0");
        ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Acme " + suffix);
        Owner owner = inventory.createOwner("Platform " + suffix);

        assertThat(inventory.getBusinessService(service.getId())).isEqualTo(service);
        assertThat(inventory.getBusinessService(service.getId()).getCriticality()).isEqualTo(BusinessCriticality.CRITICAL);
        assertThat(inventory.getSoftwareComponent(component.getId()).getVersion()).isEqualTo("7.0.0");
        assertThat(inventory.getThirdPartyVendor(vendor.getId())).isEqualTo(vendor);
        assertThat(inventory.getOwner(owner.getId())).isEqualTo(owner);

        assertThat(inventory.listBusinessServices(PAGE).getContent()).contains(service);
        assertThat(inventory.listSoftwareComponents(PAGE).getContent()).contains(component);
        assertThat(inventory.listThirdPartyVendors(PAGE).getContent()).contains(vendor);
        assertThat(inventory.listOwners(PAGE).getContent()).contains(owner);

        BusinessService updated = inventory.updateBusinessService(
                service.getId(), "Checkout " + suffix, BusinessCriticality.HIGH);
        assertThat(updated.getName()).startsWith("Checkout");
        assertThat(updated.getCriticality()).isEqualTo(BusinessCriticality.HIGH);
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
}
