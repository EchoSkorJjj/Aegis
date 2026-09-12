package com.aegis.core.inventory.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

interface BusinessServiceJpaRepository extends JpaRepository<BusinessServiceEntity, UUID> {
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}

interface SoftwareComponentJpaRepository extends JpaRepository<SoftwareComponentEntity, UUID> {
    boolean existsByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCase(String ecosystem, String name, String version);
    boolean existsByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCaseAndIdNot(
            String ecosystem, String name, String version, UUID id);

    @Query("select c from SoftwareComponentEntity c where c.id in "
            + "(select r.componentId from ServiceComponentEntity r where r.businessServiceId = :serviceId)")
    Page<SoftwareComponentEntity> findForService(@Param("serviceId") UUID serviceId, Pageable pageable);

    @Query("select c from SoftwareComponentEntity c where c.id in "
            + "(select r.dependencyId from ComponentDependencyEntity r where r.componentId = :componentId)")
    Page<SoftwareComponentEntity> findDependencies(@Param("componentId") UUID componentId, Pageable pageable);
}

interface ThirdPartyVendorJpaRepository extends JpaRepository<ThirdPartyVendorEntity, UUID> {
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    @Query("select v from ThirdPartyVendorEntity v where v.id in "
            + "(select r.vendorId from ServiceVendorEntity r where r.businessServiceId = :serviceId)")
    Page<ThirdPartyVendorEntity> findForService(@Param("serviceId") UUID serviceId, Pageable pageable);
}

interface OwnerJpaRepository extends JpaRepository<OwnerEntity, UUID> {
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    @Query("select o from OwnerEntity o where o.id in "
            + "(select r.ownerId from ServiceOwnerEntity r where r.businessServiceId = :serviceId)")
    Page<OwnerEntity> findForService(@Param("serviceId") UUID serviceId, Pageable pageable);
}

interface ServiceComponentJpaRepository extends JpaRepository<ServiceComponentEntity, ServiceComponentEntity.Key> {
}

interface ServiceVendorJpaRepository extends JpaRepository<ServiceVendorEntity, ServiceVendorEntity.Key> {
}

interface ServiceOwnerJpaRepository extends JpaRepository<ServiceOwnerEntity, ServiceOwnerEntity.Key> {
}

interface ComponentDependencyJpaRepository extends JpaRepository<ComponentDependencyEntity, ComponentDependencyEntity.Key> {
}
