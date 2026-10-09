/**
 * Audit — the decision audit trail (E10): every step a response (a risk ticket, its upgrade pull request, a
 * regulator memo) takes, with who took it, when, why, and the response as it stood then, so an organization can
 * prove how each risk was handled. Append-only: the database refuses any update or delete (V36).
 *
 * <p>{@code actions} writes it, through {@link com.aegis.core.audit.DecisionTrail#recorded}, in the same
 * transaction as each step; nothing here depends on {@code actions}. identity-service's {@code audit_events} is a
 * different log (sign-ins and account changes).
 */
package com.aegis.core.audit;
