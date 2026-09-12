package com.aegis.core.inventory.domain;

import java.util.UUID;

public final class ThirdPartyVendor extends InventoryEntity {

    private final String name;

    public ThirdPartyVendor(UUID id, String name) {
        super(id);
        this.name = requireText(name, "name");
    }

    public String getName() {
        return name;
    }
}
