package com.aegis.core.inventory.persistence;

import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

interface BusinessServiceJpaRepository extends JpaRepository<BusinessService, UUID> {
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}

interface SoftwareComponentJpaRepository extends JpaRepository<SoftwareComponent, UUID> {
    boolean existsByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCase(String ecosystem, String name, String version);
    boolean existsByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCaseAndIdNot(
            String ecosystem, String name, String version, UUID id);

    @Query("select c from SoftwareComponent c where c.id in "
            + "(select r.componentId from ServiceComponentEntity r where r.businessServiceId = :serviceId)")
    Page<SoftwareComponent> findForService(@Param("serviceId") UUID serviceId, Pageable pageable);

    @Query("select c from SoftwareComponent c where c.id in "
            + "(select r.dependencyId from ComponentDependencyEntity r where r.componentId = :componentId)")
    Page<SoftwareComponent> findDependencies(@Param("componentId") UUID componentId, Pageable pageable);
}

interface ThirdPartyVendorJpaRepository extends JpaRepository<ThirdPartyVendor, UUID> {
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    @Query("select v from ThirdPartyVendor v where v.id in "
            + "(select r.vendorId from ServiceVendorEntity r where r.businessServiceId = :serviceId)")
    Page<ThirdPartyVendor> findForService(@Param("serviceId") UUID serviceId, Pageable pageable);
}

interface OwnerJpaRepository extends JpaRepository<Owner, UUID> {
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    @Query("select o from Owner o where o.id in "
            + "(select r.ownerId from ServiceOwnerEntity r where r.businessServiceId = :serviceId)")
    Page<Owner> findForService(@Param("serviceId") UUID serviceId, Pageable pageable);
}

interface ServiceComponentJpaRepository extends JpaRepository<ServiceComponentEntity, ServiceComponentEntity.Key> {
}

interface ServiceVendorJpaRepository extends JpaRepository<ServiceVendorEntity, ServiceVendorEntity.Key> {
}

interface ServiceOwnerJpaRepository extends JpaRepository<ServiceOwnerEntity, ServiceOwnerEntity.Key> {
}

interface ComponentDependencyJpaRepository extends JpaRepository<ComponentDependencyEntity, ComponentDependencyEntity.Key> {
}
