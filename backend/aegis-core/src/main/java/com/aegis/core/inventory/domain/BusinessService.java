package com.aegis.core.inventory.domain;

import java.util.UUID;

public final class BusinessService extends InventoryEntity {

    private final String name;
    private final BusinessCriticality criticality;

    public BusinessService(UUID id, String name, BusinessCriticality criticality) {
        super(id);
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
