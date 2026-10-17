package com.aegis.core.actions.ticket;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.actions.upgrade.ResponseDirectory;
import org.springframework.stereotype.Component;

/**
 * The responses the recommendations and the triage queue link to: each service's newest ticket per change that
 * isn't dismissed, and its most recently dismissed one.
 */
@Component
class TicketResponseDirectory implements ResponseDirectory {

    private final RiskTicketStore store;

    TicketResponseDirectory(RiskTicketStore store) {
        this.store = store;
    }

    @Override
    public Map<ServiceChange, ResponseRef> current(Collection<UUID> serviceIds) {
        return byChange(store.findCurrent(serviceIds));
    }

    @Override
    public Map<ServiceChange, ResponseRef> dismissed(Collection<UUID> serviceIds) {
        return byChange(store.findDismissed(serviceIds));
    }

    /** The first ticket per service and change, in the order given. */
    private static Map<ServiceChange, ResponseRef> byChange(List<RiskTicket> tickets) {
        Map<ServiceChange, ResponseRef> byChange = new LinkedHashMap<>();
        for (RiskTicket ticket : tickets) {
            byChange.putIfAbsent(new ServiceChange(ticket.serviceId(), ticket.changeSignalId()), new ResponseRef(
                    ticket.id(), ticket.changeSignalId(), ticket.status().name(), ResponseStage.of(ticket).name(),
                    ticket.issueKey(), ticket.issueUrl()));
        }
        return byChange;
    }
}
