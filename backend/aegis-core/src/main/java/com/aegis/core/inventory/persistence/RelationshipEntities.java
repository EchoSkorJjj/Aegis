package com.aegis.core.inventory.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

// Relationship rows carry org_id (ADR §6) so tenant scoping and RLS reach the edges too, not just the
// entities. Both endpoints of an edge are always in the same org, so it equals either endpoint's org.

@Entity
@Table(name = "business_service_components")
@IdClass(ServiceComponentEntity.Key.class)
class ServiceComponentEntity {
    @Id @Column(name = "business_service_id") private UUID businessServiceId;
    @Id @Column(name = "component_id") private UUID componentId;
    @Column(name = "org_id", nullable = false) private UUID orgId;
    @Column(name = "manifest_path", length = 400) private String manifestPath;
    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ServiceComponentEntity() {
    }

    ServiceComponentEntity(UUID businessServiceId, UUID componentId, UUID orgId) {
        this.businessServiceId = businessServiceId;
        this.componentId = componentId;
        this.orgId = orgId;
    }

    UUID getBusinessServiceId() {
        return businessServiceId;
    }

    UUID getComponentId() {
        return componentId;
    }

    String getManifestPath() {
        return manifestPath;
    }

    void setManifestPath(String manifestPath) {
        this.manifestPath = manifestPath;
    }

    static final class Key implements Serializable {
        private UUID businessServiceId;
        private UUID componentId;

        public Key() {
        }

        Key(UUID businessServiceId, UUID componentId) {
            this.businessServiceId = businessServiceId;
            this.componentId = componentId;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Key key && Objects.equals(businessServiceId, key.businessServiceId)
                    && Objects.equals(componentId, key.componentId);
        }

        @Override public int hashCode() {
            return Objects.hash(businessServiceId, componentId);
        }
    }
}

@Entity
@Table(name = "business_service_vendors")
@IdClass(ServiceVendorEntity.Key.class)
class ServiceVendorEntity {
    @Id @Column(name = "business_service_id") private UUID businessServiceId;
    @Id @Column(name = "vendor_id") private UUID vendorId;
    @Column(name = "org_id", nullable = false) private UUID orgId;
    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ServiceVendorEntity() {
    }

    ServiceVendorEntity(UUID businessServiceId, UUID vendorId, UUID orgId) {
        this.businessServiceId = businessServiceId;
        this.vendorId = vendorId;
        this.orgId = orgId;
    }

    UUID getBusinessServiceId() {
        return businessServiceId;
    }

    UUID getVendorId() {
        return vendorId;
    }

    static final class Key implements Serializable {
        private UUID businessServiceId;
        private UUID vendorId;
        public Key() { }
        Key(UUID businessServiceId, UUID vendorId) { this.businessServiceId = businessServiceId; this.vendorId = vendorId; }
        @Override public boolean equals(Object o) { return o instanceof Key key && Objects.equals(businessServiceId, key.businessServiceId) && Objects.equals(vendorId, key.vendorId); }
        @Override public int hashCode() { return Objects.hash(businessServiceId, vendorId); }
    }
}

@Entity
@Table(name = "business_service_owners")
@IdClass(ServiceOwnerEntity.Key.class)
class ServiceOwnerEntity {
    @Id @Column(name = "business_service_id") private UUID businessServiceId;
    @Id @Column(name = "owner_id") private UUID ownerId;
    @Column(name = "org_id", nullable = false) private UUID orgId;
    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ServiceOwnerEntity() {
    }

    ServiceOwnerEntity(UUID businessServiceId, UUID ownerId, UUID orgId) {
        this.businessServiceId = businessServiceId;
        this.ownerId = ownerId;
        this.orgId = orgId;
    }

    UUID getBusinessServiceId() {
        return businessServiceId;
    }

    UUID getOwnerId() {
        return ownerId;
    }

    static final class Key implements Serializable {
        private UUID businessServiceId;
        private UUID ownerId;
        public Key() { }
        Key(UUID businessServiceId, UUID ownerId) { this.businessServiceId = businessServiceId; this.ownerId = ownerId; }
        @Override public boolean equals(Object o) { return o instanceof Key key && Objects.equals(businessServiceId, key.businessServiceId) && Objects.equals(ownerId, key.ownerId); }
        @Override public int hashCode() { return Objects.hash(businessServiceId, ownerId); }
    }
}

@Entity
@Table(name = "software_component_dependencies")
@IdClass(ComponentDependencyEntity.Key.class)
class ComponentDependencyEntity {
    @Id @Column(name = "component_id") private UUID componentId;
    @Id @Column(name = "dependency_id") private UUID dependencyId;
    @Column(name = "org_id", nullable = false) private UUID orgId;
    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ComponentDependencyEntity() {
    }

    ComponentDependencyEntity(UUID componentId, UUID dependencyId, UUID orgId) {
        this.componentId = componentId;
        this.dependencyId = dependencyId;
        this.orgId = orgId;
    }

    UUID getComponentId() {
        return componentId;
    }

    UUID getDependencyId() {
        return dependencyId;
    }

    static final class Key implements Serializable {
        private UUID componentId;
        private UUID dependencyId;
        public Key() { }
        Key(UUID componentId, UUID dependencyId) { this.componentId = componentId; this.dependencyId = dependencyId; }
        @Override public boolean equals(Object o) { return o instanceof Key key && Objects.equals(componentId, key.componentId) && Objects.equals(dependencyId, key.dependencyId); }
        @Override public int hashCode() { return Objects.hash(componentId, dependencyId); }
    }
}
