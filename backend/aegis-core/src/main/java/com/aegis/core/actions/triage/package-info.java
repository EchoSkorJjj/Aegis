/**
 * E9's triage queue: what to respond to next, worst first. Built on the upgrade recommendations
 * ({@code actions.upgrade}) and the vendor outages still in progress, each item with the response already drafted
 * for it. Recomputed per request; responses are drafted and decided in {@code actions.ticket}.
 */
package com.aegis.core.actions.triage;
