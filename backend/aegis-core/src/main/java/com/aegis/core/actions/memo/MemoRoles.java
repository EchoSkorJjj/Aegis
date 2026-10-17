package com.aegis.core.actions.memo;

/**
 * Method-security expressions for regulator memos (ADR §6e). A memo covers every service a change affects,
 * so only an {@code ANALYST} drafts and decides one; a {@code COMPLIANCE_ADMIN} audits it and a
 * {@code SERVICE_OWNER} reads it. Whoever drafted a memo never approves it
 * ({@link RegulatorMemoService#approve}). Reading needs any domain role ({@code InventoryRoles.READ}).
 */
final class MemoRoles {

    /** Draft, approve or dismiss a memo. */
    static final String ANALYST = "hasRole('ANALYST')";

    private MemoRoles() {
    }
}
