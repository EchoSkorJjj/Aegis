/**
 * Actions — executes the responses a person has approved at the trust boundary: open a pull request,
 * file a risk ticket, send a notification memo. Every execution is gated by human approval and
 * written to the append-only audit trail once those workflows are implemented.
 *
 * <p>Provides the draft-PR port, validated proposal types, provider registry, and a GitHub App spike
 * disabled by default. No execution endpoint, scheduler or approval workflow exists yet. The spike's
 * account allow-list must be replaced by verified org-scoped installation bindings before production.
 *
 * <p>Last module to be extracted — its own credentials and blast radius (the GitHub App key, tracker
 * tokens) justify a separate service only later (see ARCHITECTURE.md growth path).
 */
package com.aegis.core.actions;
