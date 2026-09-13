package com.aegis.core.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.NaturalId;

import java.util.UUID;

@Entity
@Table(name = "third_party_vendors")
public class ThirdPartyVendor extends InventoryEntity {

    @NaturalId(mutable = true)
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    protected ThirdPartyVendor() {
        // Required by JPA.
    }

    public ThirdPartyVendor(UUID id, String name) {
        super(id);
        this.name = requireText(name, "name");
    }

    public void rename(String name) {
        this.name = requireText(name, "name");
    }

    public String getName() {
        return name;
    }
}
