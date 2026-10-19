package com.aegis.core.actions.assess;

/**
 * Who may ask the AI to assess an item (ADR §6e): whoever may draft a response for the service — an {@code ANALYST},
 * or one of its assigned {@code SERVICE_OWNER}s. Reading an answer needs any domain role ({@code InventoryRoles.READ}).
 */
final class AssessRoles {

    /** The method must take the request as a parameter named {@code request}. */
    static final String ASK = "hasRole('ANALYST')"
            + " or (hasRole('SERVICE_OWNER') and @serviceOwnership.isAssignedOwner(#request?.serviceId(), authentication))";

    private AssessRoles() {
    }
}
