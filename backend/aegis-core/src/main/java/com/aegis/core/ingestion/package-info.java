/**
 * Ingestion — the framework that polls external feeds (advisories, vendor status, threat intel) on a
 * schedule and captures what they return in an append-only, de-duplicated raw-event store.
 *
 * <p>The seam is {@link com.aegis.core.ingestion.IngestionSource}: each feed connector implements it
 * as a Spring bean, and the {@link com.aegis.core.ingestion.IngestionScheduler} discovers and polls
 * them, applying per-source retry/backoff and isolating one feed's failure from the others.
 *
 * <p>Connectors (CVE/NVD, vendor status, security news) and the normalisation of raw events into
 * {@code Change}/{@code Signal} records are later stories; this package is the framework only.
 * Deterministic — no AI here.
 */
package com.aegis.core.ingestion;
