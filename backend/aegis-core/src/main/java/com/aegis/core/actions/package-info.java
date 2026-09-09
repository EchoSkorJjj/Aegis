/**
 * Actions — executes the responses a person has approved at the trust boundary: open a pull request,
 * file a risk ticket, send a notification memo. Every execution is gated by human approval and
 * written to the append-only audit trail; until an integration exists, "execute" records the
 * approval and surfaces the draft to copy.
 *
 * <p>Last module to be extracted — its own credentials and blast radius (the GitHub App key, tracker
 * tokens) justify a separate service only later (see ARCHITECTURE.md growth path).
 */
package com.aegis.core.actions;
