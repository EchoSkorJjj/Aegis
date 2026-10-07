package com.aegis.core.actions.ticket;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.actions.upgrade.ResponseDirectory;
import org.springframework.stereotype.Component;

/** The current responses the recommendations link to: each service's newest ticket per change, not dismissed. */
@Component
class TicketResponseDirectory implements ResponseDirectory {

    private final RiskTicketStore store;

    TicketResponseDirectory(RiskTicketStore store) {
        this.store = store;
    }

    @Override
    public Map<ServiceChange, ResponseRef> current(Collection<UUID> serviceIds) {
        Map<ServiceChange, ResponseRef> current = new LinkedHashMap<>();
        for (RiskTicket ticket : store.findCurrent(serviceIds)) {
            current.putIfAbsent(new ServiceChange(ticket.serviceId(), ticket.changeSignalId()), new ResponseRef(
                    ticket.id(), ticket.changeSignalId(), ticket.status().name(), ResponseStage.of(ticket).name(),
                    ticket.issueKey(), ticket.issueUrl()));
        }
        return current;
    }
}
