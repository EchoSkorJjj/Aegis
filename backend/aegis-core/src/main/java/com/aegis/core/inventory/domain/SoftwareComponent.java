package com.aegis.core.inventory.domain;

import java.util.UUID;

public final class SoftwareComponent extends InventoryEntity {

    private final String ecosystem;
    private final String name;
    private final String version;

    public SoftwareComponent(UUID id, String ecosystem, String name, String version) {
        super(id);
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
