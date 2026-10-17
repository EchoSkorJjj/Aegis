package com.aegis.core.actions.ticket;

/**
 * Method-security expressions for responses (risk tickets and their pull requests), one duty per role (ADR §6e).
 * An {@code ANALYST} drafts and decides for any service, a {@code SERVICE_OWNER} for the services they are an
 * assigned owner of; a {@code COMPLIANCE_ADMIN} audits and does neither. Whoever drafted a response with a pull
 * request never approves it ({@link RiskTicketService#approve}). Reading needs any domain role
 * ({@code InventoryRoles.READ}).
 */
final class TicketRoles {

    /** Draft a response. The method must take the request as a parameter named {@code request}. */
    static final String PROPOSE = "hasRole('ANALYST')"
            + " or (hasRole('SERVICE_OWNER') and @serviceOwnership.isAssignedOwner(#request?.serviceId(), authentication))";

    /** Approve or dismiss. The method must take the ticket's id as a parameter named {@code id}. */
    static final String DECIDE = "hasRole('ANALYST')"
            + " or (hasRole('SERVICE_OWNER') and @riskTicketAccess.isOwnerOfTicketService(#id, authentication))";

    private TicketRoles() {
    }
}
