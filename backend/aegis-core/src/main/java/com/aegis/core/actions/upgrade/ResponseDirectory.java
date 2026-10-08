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

    /**
     * Each service's most recently dismissed response per change. Where a change has no current response too,
     * its item is closed: someone decided no response was needed, until another is drafted.
     */
    Map<ServiceChange, ResponseRef> dismissed(Collection<UUID> serviceIds);

    record ServiceChange(UUID serviceId, UUID changeSignalId) {
    }

    /**
     * A response as a recommendation shows it: its ticket, how far along it is — its own status, and its item's
     * stage (In review, Approved, Overridden, Actioned or Closed) — and its key once filed.
     */
    record ResponseRef(UUID ticketId, UUID changeSignalId, String status, String stage, String issueKey,
                       String issueUrl) {
    }
}
