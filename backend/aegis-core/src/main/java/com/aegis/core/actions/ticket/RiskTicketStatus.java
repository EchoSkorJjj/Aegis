package com.aegis.core.actions.ticket;

/**
 * A risk ticket's life: drafted and awaiting a decision, being filed, filed in the organization's tracker,
 * failed to file (it can be approved again or dismissed), or dismissed with a reason.
 */
public enum RiskTicketStatus {
    PROPOSED,
    FILING,
    FILED,
    FAILED,
    DISMISSED
}
