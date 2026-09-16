/**
 * Ingestion — the framework that polls external feeds (advisories, vendor status, threat intel) on a
 * schedule and captures what they return in an append-only, de-duplicated raw-event store.
 *
 * <p>The seam is {@link com.aegis.core.ingestion.IngestionSource}: each feed connector implements it
 * as a Spring bean, and the {@link com.aegis.core.ingestion.IngestionScheduler} discovers and polls
 * them, applying per-source retry/backoff and isolating one feed's failure from the others.
 *
 * <p>This package is the framework; {@link com.aegis.core.ingestion.nvd} is the first connector and
 * {@link com.aegis.core.ingestion.advisory} holds the normalised output it feeds to matching. Vendor
 * status and security-news connectors are later stories. Deterministic — no AI here.
 */
package com.aegis.core.ingestion;
