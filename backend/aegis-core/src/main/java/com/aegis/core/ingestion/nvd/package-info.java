/**
 * The NVD (National Vulnerability Database) connector — the first concrete feed behind the ingestion
 * framework's {@link com.aegis.core.ingestion.IngestionSource} seam.
 *
 * <p>Two halves, deliberately decoupled through the raw-event store:
 * {@link com.aegis.core.ingestion.nvd.NvdSource} polls the CVE API 2.0 and captures each CVE exactly
 * as returned, and {@link com.aegis.core.ingestion.nvd.NvdNormalizer} later turns those payloads into
 * {@link com.aegis.core.ingestion.advisory.Advisory} records via
 * {@link com.aegis.core.ingestion.nvd.NvdAdvisoryParser}.
 *
 * <p>NVD data is <b>untrusted external content</b>: payloads are size-capped, every field is treated
 * as optional, and nothing here is fed to an LLM. Deterministic; no AI.
 */
package com.aegis.core.ingestion.nvd;
