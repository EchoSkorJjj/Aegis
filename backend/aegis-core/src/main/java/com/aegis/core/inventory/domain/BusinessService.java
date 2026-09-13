package com.aegis.core.inventory.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.NaturalId;

@Entity
@Table(name = "business_services")
public class BusinessService extends InventoryEntity {

    @NaturalId(mutable = true)
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "criticality", nullable = false, length = 16)
    private BusinessCriticality criticality;

    protected BusinessService() {
        // Required by JPA.
    }

    public BusinessService(UUID id, String name, BusinessCriticality criticality) {
        super(id);
        this.name = requireText(name, "name");
        this.criticality = requireNonNull(criticality, "criticality");
    }

    public void update(String name, BusinessCriticality criticality) {
        this.name = requireText(name, "name");
        this.criticality = requireNonNull(criticality, "criticality");
    }

    public String getName() {
        return name;
    }

    public BusinessCriticality getCriticality() {
        return criticality;
    }
}
