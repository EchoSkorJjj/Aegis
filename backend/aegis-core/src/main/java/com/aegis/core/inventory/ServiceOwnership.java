package com.aegis.core.inventory;

import java.util.UUID;

import com.aegis.core.platform.common.ApiExceptions;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The method-security check behind {@link InventoryRoles#COMPOSE}: is the caller one of a service's
 * assigned owners? The caller is identified by the access token's subject — their identity-service
 * user id — and the lookup is org-scoped like every inventory read, so a service in another
 * organization is never "owned". Anything that cannot be resolved denies.
 */
@Component("serviceOwnership")
public class ServiceOwnership {

    private final InventoryService inventory;

    public ServiceOwnership(InventoryService inventory) {
        this.inventory = inventory;
    }

    public boolean isAssignedOwner(UUID serviceId, Authentication authentication) {
        if (serviceId == null || authentication == null || authentication.getName() == null) {
            return false;
        }
        UUID userId;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException notAUserId) {
            return false;
        }
        try {
            return inventory.isAssignedOwner(serviceId, userId);
        } catch (ApiExceptions.Forbidden noOrganizationContext) {
            return false;
        }
    }
}
