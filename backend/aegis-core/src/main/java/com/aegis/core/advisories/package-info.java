/**
 * Normalised advisories — the output of ingestion and the input to matching.
 *
 * <p>Connectors capture raw feed payloads; a normaliser turns those into {@link
 * com.aegis.core.advisories.Advisory} records: a single comparable CVSS metric plus the
 * affected (vendor, product, version-range) tuples in {@link
 * com.aegis.core.advisories.AffectedProduct}. Nothing here interprets version ordering or
 * decides relevance — that is matching's and scoring's work. Deterministic; no AI.
 */
package com.aegis.core.advisories;
