package com.aegis.core.inventory.persistence;

import com.aegis.core.inventory.domain.BusinessCriticality;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
abstract class InventoryRecordEntity {

    @Id
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InventoryRecordEntity() {
    }

    protected InventoryRecordEntity(UUID id) {
        Instant now = Instant.now();
        this.id = id;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID id() {
        return id;
    }

    void touch() {
        this.updatedAt = Instant.now();
    }
}

@Entity
@Table(name = "business_services")
class BusinessServiceEntity extends InventoryRecordEntity {

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BusinessCriticality criticality;

    protected BusinessServiceEntity() {
    }

    BusinessServiceEntity(UUID id, String name, BusinessCriticality criticality) {
        super(id);
        this.name = name;
        this.criticality = criticality;
    }

    String name() {
        return name;
    }

    BusinessCriticality criticality() {
        return criticality;
    }

    void update(String name, BusinessCriticality criticality) {
        this.name = name;
        this.criticality = criticality;
        touch();
    }
}

@Entity
@Table(name = "software_components")
class SoftwareComponentEntity extends InventoryRecordEntity {

    @Column(nullable = false, length = 64)
    private String ecosystem;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 128)
    private String version;

    protected SoftwareComponentEntity() {
    }

    SoftwareComponentEntity(UUID id, String ecosystem, String name, String version) {
        super(id);
        this.ecosystem = ecosystem;
        this.name = name;
        this.version = version;
    }

    String ecosystem() {
        return ecosystem;
    }

    String name() {
        return name;
    }

    String version() {
        return version;
    }

    void update(String ecosystem, String name, String version) {
        this.ecosystem = ecosystem;
        this.name = name;
        this.version = version;
        touch();
    }
}

@Entity
@Table(name = "third_party_vendors")
class ThirdPartyVendorEntity extends InventoryRecordEntity {

    @Column(nullable = false, length = 255)
    private String name;

    protected ThirdPartyVendorEntity() {
    }

    ThirdPartyVendorEntity(UUID id, String name) {
        super(id);
        this.name = name;
    }

    String name() {
        return name;
    }

    void update(String name) {
        this.name = name;
        touch();
    }
}

@Entity
@Table(name = "owners")
class OwnerEntity extends InventoryRecordEntity {

    @Column(nullable = false, length = 255)
    private String name;

    protected OwnerEntity() {
    }

    OwnerEntity(UUID id, String name) {
        super(id);
        this.name = name;
    }

    String name() {
        return name;
    }

    void update(String name) {
        this.name = name;
        touch();
    }
}
