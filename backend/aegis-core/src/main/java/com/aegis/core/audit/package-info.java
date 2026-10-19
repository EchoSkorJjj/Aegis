/**
 * Audit — the decision audit trail (E10): every step a response (a risk ticket, its upgrade pull request, a
 * regulator memo) takes, with who took it, when, why, and the response as it stood then, so an organization can
 * prove how each risk was handled. Append-only: the database refuses any update or delete (V36).
 *
 * <p>{@code actions} writes it, through {@link com.aegis.core.audit.DecisionTrail#recorded}, in the same
 * transaction as each step, and its response stores refuse any write made otherwise; nothing here depends on
 * {@code actions}. identity-service's {@code audit_events} is a different log (sign-ins and account changes).
 *
 * <p>It is read by searching it ({@link com.aegis.core.audit.DecisionTrailController}, E10 story 2) and by exporting a
 * service's evidence for a period as PDF or CSV ({@link com.aegis.core.audit.EvidenceController}, E10 story 3), which
 * names the service from {@code inventory} and the people from the member directory.
 */
package com.aegis.core.audit;
