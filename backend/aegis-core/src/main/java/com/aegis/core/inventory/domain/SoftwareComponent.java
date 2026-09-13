package com.aegis.core.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.NaturalId;

import java.util.UUID;

@Entity
@Table(name = "software_components")
public class SoftwareComponent extends InventoryEntity {

    @NaturalId(mutable = true)
    @Column(name = "ecosystem", nullable = false, length = 64)
    private String ecosystem;

    @NaturalId(mutable = true)
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @NaturalId(mutable = true)
    @Column(name = "version", nullable = false, length = 128)
    private String version;

    protected SoftwareComponent() {
        // Required by JPA.
    }

    public SoftwareComponent(UUID id, String ecosystem, String name, String version) {
        super(id);
        this.ecosystem = requireText(ecosystem, "ecosystem");
        this.name = requireText(name, "name");
        this.version = requireText(version, "version");
    }

    public void update(String ecosystem, String name, String version) {
        this.ecosystem = requireText(ecosystem, "ecosystem");
        this.name = requireText(name, "name");
        this.version = requireText(version, "version");
    }

    public String getEcosystem() {
        return ecosystem;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }
}
