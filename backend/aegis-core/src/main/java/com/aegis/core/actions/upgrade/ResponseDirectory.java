package com.aegis.core.actions.upgrade;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The responses (risk tickets) drafted for changes in services, so a recommendation links to the one already
 * drafted instead of offering to draft it again. The ticket module implements it; this module only asks,
 * which keeps the dependency one way (tickets use upgrades, never the reverse).
 */
public interface ResponseDirectory {

    /** Each service's current response (not dismissed) per change. */
    Map<ServiceChange, ResponseRef> current(Collection<UUID> serviceIds);

    record ServiceChange(UUID serviceId, UUID changeSignalId) {
    }

    /** A response as a recommendation shows it: its ticket, how far along it is, and its key once filed. */
    record ResponseRef(UUID ticketId, UUID changeSignalId, String status, String issueKey, String issueUrl) {
    }
}
