package com.aegis.core.actions.upgrade;

/**
 * Method-security expressions for safe-upgrade proposals, one duty per role (ADR §6d): an {@code ANALYST}
 * drafts and decides for any service, and a {@code SERVICE_OWNER} for the services they are an assigned
 * owner of. A {@code COMPLIANCE_ADMIN} audits these decisions and makes none of them. On top of the role,
 * whoever drafted a proposal never approves it ({@link UpgradeProposalService#approve}). Reading needs any
 * domain role ({@code InventoryRoles.READ}).
 */
final class UpgradeRoles {

    /** The authority that drafts and decides for every service, as {@link #PROPOSE} and {@link #DECIDE} say. */
    static final String ANY_SERVICE = "ROLE_ANALYST";

    /** Draft a proposal. The method must take the request as a parameter named {@code request}. */
    static final String PROPOSE = "hasRole('ANALYST')"
            + " or (hasRole('SERVICE_OWNER') and @serviceOwnership.isAssignedOwner(#request?.serviceId(), authentication))";

    /** Approve or dismiss. The method must take the proposal's id as a parameter named {@code id}. */
    static final String DECIDE = "hasRole('ANALYST')"
            + " or (hasRole('SERVICE_OWNER') and @upgradeProposalAccess.isOwnerOfProposalService(#id, authentication))";

    private UpgradeRoles() {
    }
}
