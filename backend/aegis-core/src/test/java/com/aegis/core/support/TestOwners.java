package com.aegis.core.support;

import java.util.UUID;

import com.aegis.core.inventory.InventoryRoles;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.platform.identity.DirectoryUser;

/**
 * Test helper: make a (fake) organization member holding {@code SERVICE_OWNER} an owner of a service,
 * through the real assignment path ({@link InventoryService#assignServiceOwner}) — so tests exercise
 * the same verification production does. Must run inside an org context (e.g. an
 * {@link AbstractInventoryIntegrationTest}).
 */
public class TestOwners {

    private final FakeIdentityDirectory directory;
    private final InventoryService inventory;

    public TestOwners(FakeIdentityDirectory directory, InventoryService inventory) {
        this.directory = directory;
        this.inventory = inventory;
    }

    /** A new service-owner member named {@code displayName}, assigned to the service. */
    public Owner assign(UUID serviceId, String displayName) {
        DirectoryUser member = directory.member(displayName, InventoryRoles.SERVICE_OWNER);
        return inventory.assignServiceOwner(serviceId, member.id());
    }

    /** Assign an already-registered member (e.g. to give one person several services). */
    public Owner assign(UUID serviceId, DirectoryUser member) {
        return inventory.assignServiceOwner(serviceId, member.id());
    }

    public FakeIdentityDirectory directory() {
        return directory;
    }
}
