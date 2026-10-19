package com.aegis.core.incidents;

import java.time.Instant;
import java.util.UUID;

/**
 * A normalised vendor status incident — an outage or degradation published by a third party the
 * estate depends on. The counterpart to an advisory: where an advisory says a component is
 * vulnerable, this says a supplier is impaired.
 *
 * <p>Matching resolves {@code vendorKey}/{@code vendorName} against the inventory's registered
 * vendors to reach the impacted services. {@code status} and {@code impact} keep the feed's own
 * vocabulary (Statuspage: {@code investigating}/{@code identified}/{@code monitoring}/{@code resolved},
 * and {@code none}/{@code minor}/{@code major}/{@code critical}); a plain RSS feed supplies neither,
 * so both may be null.
 *
 * <p>{@code id} is null on a freshly parsed incident — the store assigns it on first write.
 */
public record VendorIncident(
        UUID id,
        String sourceId,
        String externalId,
        String vendorKey,
        String vendorName,
        String title,
        String status,
        String impact,
        String url,
        String summary,
        Instant startedAt,
        Instant lastUpdateAt,
        Instant resolvedAt) {

    /** True once the vendor has declared the incident over. */
    public boolean resolved() {
        return resolvedAt != null || "resolved".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status);
    }
}
