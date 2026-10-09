/**
 * Actions — executes the responses a person has approved at the trust boundary: open a draft pull request,
 * file a risk ticket, make a regulator memo final (Aegis never sends it). Every execution is gated by human approval,
 * and every step a response takes is written to the append-only decision trail ({@code com.aegis.core.audit}).
 *
 * <p>{@code actions.pr} is the draft-PR port, its validated proposal types and provider registry, with a
 * GitHub App adapter disabled by default ({@code actions.pr.github}) that mints tokens only for installations an
 * organization has connected. {@code actions.upgrade} is the first response that uses it (E8 story 4): safe-upgrade
 * proposals a person approves or dismisses, the only path that opens a pull request. There is no scheduler and
 * nothing merges.
 *
 * <p>Last module to be extracted — its own credentials and blast radius (the GitHub App key, tracker
 * tokens) justify a separate service only later (see ARCHITECTURE.md growth path).
 */
package com.aegis.core.actions;
