package com.aegis.core.inventory;

import java.time.Instant;
import java.util.UUID;

import com.aegis.core.inventory.domain.BusinessCriticality;

/**
 * Optional list filters for the inventory read endpoints. Every field is nullable/blank-tolerant:
 * a null or blank value means "no constraint on this dimension". The persistence adapter turns a
 * filter into a JPA {@code Specification}, always ANDed with the caller's org scope (ADR §6).
 */
public final class InventoryFilters {

    private InventoryFilters() {
    }

    /** Business-service list filters: name substring, exact criticality, and a created-at window. */
    public record ServiceFilter(
            String search, BusinessCriticality criticality, Instant createdAfter, Instant createdBefore) {
        public static final ServiceFilter NONE = new ServiceFilter(null, null, null, null);
    }

    /**
     * Component list filters: name/version substring, exact ecosystem, a created-at window, and an
     * optional {@code excludeServiceId} that drops components already linked to that service (so the
     * "link a component" picker never offers one the service already has).
     */
    public record ComponentFilter(
            String search, String ecosystem, Instant createdAfter, Instant createdBefore, UUID excludeServiceId) {
        public static final ComponentFilter NONE = new ComponentFilter(null, null, null, null, null);
    }

    /**
     * Vendor / owner list filters: name substring, a created-at window, and an optional
     * {@code excludeServiceId} that drops rows already linked to that service.
     */
    public record NamedFilter(String search, Instant createdAfter, Instant createdBefore, UUID excludeServiceId) {
        public static final NamedFilter NONE = new NamedFilter(null, null, null, null);
    }
}
