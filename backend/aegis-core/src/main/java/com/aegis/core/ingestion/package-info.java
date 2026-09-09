/**
 * Ingestion — scheduled polling of advisory feeds (OSV / NVD / vendor status) and normalisation
 * into Advisory records (raw + parsed, de-duplicated), with a feed-health metric.
 *
 * <p>Sits behind the <b>queue seam</b>: ingestion hands normalised advisories to matching via a
 * queue, so long or bursty polls never affect API latency. First candidate to extract into a
 * standalone detector worker (see ARCHITECTURE.md). Deterministic — no AI here.
 */
package com.aegis.core.ingestion;
