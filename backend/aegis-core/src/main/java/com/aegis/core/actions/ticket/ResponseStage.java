package com.aegis.core.actions.ticket;

/**
 * Where an item stands, whatever its response's own status (E9 story 3): New → In review → Approved or
 * Overridden → Actioned or Closed. An item without a response is {@link #NEW}; a drafted one is
 * {@link #IN_REVIEW} until someone decides it. Approving moves it to {@link #APPROVED}, or {@link #OVERRIDDEN}
 * when a reviewer replaced Aegis's priority or due date, until the ticket is filed ({@link #ACTIONED}).
 * Dismissing closes it, at any point before then ({@link #CLOSED}). A failed filing stays approved or overridden:
 * the decision stands and is retried.
 */
public enum ResponseStage {
    NEW,
    IN_REVIEW,
    APPROVED,
    OVERRIDDEN,
    ACTIONED,
    CLOSED;

    public static ResponseStage of(RiskTicket ticket) {
        return switch (ticket.status()) {
            case PROPOSED -> IN_REVIEW;
            case FILING, FAILED -> ticket.override() == null ? APPROVED : OVERRIDDEN;
            case FILED -> ACTIONED;
            case DISMISSED -> CLOSED;
        };
    }
}
