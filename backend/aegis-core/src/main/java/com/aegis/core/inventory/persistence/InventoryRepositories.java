package com.aegis.core.inventory.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Every read is scoped to the caller's org (ADR §6): the adapter passes the active org and these
// methods filter on it, so one tenant never sees another's inventory. Deletes and edge existence go
// through PK lookups the service has already org-verified via an org-scoped find, and the tenant GUC
// (set by the adapter) lets Postgres RLS backstop it all.

interface BusinessServiceJpaRepository
        extends JpaRepository<BusinessService, UUID>, JpaSpecificationExecutor<BusinessService> {
    Optional<BusinessService> findByIdAndOrgId(UUID id, UUID orgId);
    Page<BusinessService> findByOrgId(UUID orgId, Pageable pageable);
    List<BusinessService> findByIdInAndOrgId(Collection<UUID> ids, UUID orgId);
    Optional<BusinessService> findByNameIgnoreCaseAndOrgId(String name, UUID orgId);
    boolean existsByNameIgnoreCaseAndOrgId(String name, UUID orgId);
    boolean existsByNameIgnoreCaseAndOrgIdAndIdNot(String name, UUID orgId, UUID id);
}

interface SoftwareComponentJpaRepository
        extends JpaRepository<SoftwareComponent, UUID>, JpaSpecificationExecutor<SoftwareComponent> {
    Optional<SoftwareComponent> findByIdAndOrgId(UUID id, UUID orgId);
    Page<SoftwareComponent> findByOrgId(UUID orgId, Pageable pageable);
    List<SoftwareComponent> findAllByOrgIdOrderByNameAscIdAsc(UUID orgId);

    @Query("select distinct c.ecosystem from SoftwareComponent c where c.orgId = :orgId order by c.ecosystem")
    List<String> findDistinctEcosystems(@Param("orgId") UUID orgId);
    List<SoftwareComponent> findByIdInAndOrgId(Collection<UUID> ids, UUID orgId);
    Optional<SoftwareComponent> findByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCaseAndOrgId(
            String ecosystem, String name, String version, UUID orgId);
    boolean existsByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCaseAndOrgId(
            String ecosystem, String name, String version, UUID orgId);
    boolean existsByEcosystemIgnoreCaseAndNameIgnoreCaseAndVersionIgnoreCaseAndOrgIdAndIdNot(
            String ecosystem, String name, String version, UUID orgId, UUID id);

    @Query("select c from SoftwareComponent c where c.orgId = :orgId and c.id in "
            + "(select r.componentId from ServiceComponentEntity r "
            + "where r.businessServiceId = :serviceId and r.orgId = :orgId)")
    Page<SoftwareComponent> findForService(
            @Param("serviceId") UUID serviceId, @Param("orgId") UUID orgId, Pageable pageable);

    @Query("select c from SoftwareComponent c where c.orgId = :orgId and c.id in "
            + "(select r.dependencyId from ComponentDependencyEntity r "
            + "where r.componentId = :componentId and r.orgId = :orgId)")
    Page<SoftwareComponent> findDependencies(
            @Param("componentId") UUID componentId, @Param("orgId") UUID orgId, Pageable pageable);
}

interface ThirdPartyVendorJpaRepository
        extends JpaRepository<ThirdPartyVendor, UUID>, JpaSpecificationExecutor<ThirdPartyVendor> {
    Optional<ThirdPartyVendor> findByIdAndOrgId(UUID id, UUID orgId);
    Page<ThirdPartyVendor> findByOrgId(UUID orgId, Pageable pageable);
    List<ThirdPartyVendor> findAllByOrgIdOrderByNameAscIdAsc(UUID orgId);
    Optional<ThirdPartyVendor> findByNameIgnoreCaseAndOrgId(String name, UUID orgId);
    boolean existsByNameIgnoreCaseAndOrgId(String name, UUID orgId);
    boolean existsByNameIgnoreCaseAndOrgIdAndIdNot(String name, UUID orgId, UUID id);

    @Query("select v from ThirdPartyVendor v where v.orgId = :orgId and v.id in "
            + "(select r.vendorId from ServiceVendorEntity r "
            + "where r.businessServiceId = :serviceId and r.orgId = :orgId)")
    Page<ThirdPartyVendor> findForService(
            @Param("serviceId") UUID serviceId, @Param("orgId") UUID orgId, Pageable pageable);
}

interface OwnerJpaRepository
        extends JpaRepository<Owner, UUID>, JpaSpecificationExecutor<Owner> {
    Optional<Owner> findByIdAndOrgId(UUID id, UUID orgId);
    Page<Owner> findByOrgId(UUID orgId, Pageable pageable);
    List<Owner> findByIdInAndOrgId(Collection<UUID> ids, UUID orgId);
    Optional<Owner> findByNameIgnoreCaseAndOrgId(String name, UUID orgId);
    boolean existsByNameIgnoreCaseAndOrgId(String name, UUID orgId);
    boolean existsByNameIgnoreCaseAndOrgIdAndIdNot(String name, UUID orgId, UUID id);

    @Query("select o from Owner o where o.orgId = :orgId and o.id in "
            + "(select r.ownerId from ServiceOwnerEntity r "
            + "where r.businessServiceId = :serviceId and r.orgId = :orgId)")
    Page<Owner> findForService(@Param("serviceId") UUID serviceId, @Param("orgId") UUID orgId, Pageable pageable);
}

interface ServiceComponentJpaRepository extends JpaRepository<ServiceComponentEntity, ServiceComponentEntity.Key> {
    List<ServiceComponentEntity> findByComponentIdInAndOrgId(Collection<UUID> componentIds, UUID orgId);
}

interface ServiceVendorJpaRepository extends JpaRepository<ServiceVendorEntity, ServiceVendorEntity.Key> {
    List<ServiceVendorEntity> findByVendorIdAndOrgId(UUID vendorId, UUID orgId);
}

interface ServiceOwnerJpaRepository extends JpaRepository<ServiceOwnerEntity, ServiceOwnerEntity.Key> {
    List<ServiceOwnerEntity> findByBusinessServiceIdInAndOrgId(Collection<UUID> businessServiceIds, UUID orgId);
}

interface ComponentDependencyJpaRepository extends JpaRepository<ComponentDependencyEntity, ComponentDependencyEntity.Key> {
    /** Direct dependents (one BFS level): components whose {@code dependencyId} is one of the given components. */
    List<ComponentDependencyEntity> findByDependencyIdInAndOrgId(Collection<UUID> dependencyIds, UUID orgId);
}
