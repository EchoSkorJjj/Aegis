package com.aegis.core.actions.ticket;

import java.util.UUID;

import com.aegis.core.inventory.ServiceOwnership;
import com.aegis.core.platform.common.ApiExceptions;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The method-security check behind {@link TicketRoles#DECIDE}: is the caller an assigned owner of the service
 * a ticket belongs to? Org-scoped like every ticket read, so another organization's ticket is never "owned";
 * anything that cannot be resolved denies.
 */
@Component("riskTicketAccess")
public class RiskTicketAccess {

    private final RiskTicketStore tickets;
    private final ServiceOwnership ownership;

    public RiskTicketAccess(RiskTicketStore tickets, ServiceOwnership ownership) {
        this.tickets = tickets;
        this.ownership = ownership;
    }

    public boolean isOwnerOfTicketService(UUID ticketId, Authentication authentication) {
        if (ticketId == null) {
            return false;
        }
        try {
            return tickets.find(ticketId)
                    .map(ticket -> ownership.isAssignedOwner(ticket.serviceId(), authentication))
                    .orElse(false);
        } catch (ApiExceptions.Forbidden noOrganizationContext) {
            return false;
        }
    }
}
