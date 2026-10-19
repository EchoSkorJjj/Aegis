package com.aegis.core.actions.upgrade;

/**
 * Method-security expressions for safe-upgrade proposals, one duty per role (ADR §6e): an {@code ANALYST}
 * decides for any service, and a {@code SERVICE_OWNER} for the services they are an assigned owner of. A
 * {@code COMPLIANCE_ADMIN} audits these decisions and makes none of them. On top of the role, whoever drafted a
 * proposal never approves it ({@link UpgradeProposalService#approve}). A proposal is drafted as part of a
 * response, under the ticket module's rules. Reading needs any domain role ({@code InventoryRoles.READ}).
 */
final class UpgradeRoles {

    /** The authority that drafts and decides for every service, as {@link #DECIDE} says. */
    static final String ANY_SERVICE = "ROLE_ANALYST";

    /** Approve or dismiss. The method must take the proposal's id as a parameter named {@code id}. */
    static final String DECIDE = "hasRole('ANALYST')"
            + " or (hasRole('SERVICE_OWNER') and @upgradeProposalAccess.isOwnerOfProposalService(#id, authentication))";

    private UpgradeRoles() {
    }
}
