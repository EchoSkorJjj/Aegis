/**
 * Ingestion connectors — the source adapters, one sub-package per external feed, kept apart from the
 * generic pipeline in {@link com.aegis.core.ingestion} so the framework and its feeds don't blur
 * together.
 *
 * <p>Each connector implements {@link com.aegis.core.ingestion.IngestionSource} to fetch raw payloads
 * (the framework tags, de-duplicates, and persists them), and pairs that with a normaliser that drains
 * captured events into the domain it feeds:
 *
 * <ul>
 *   <li>{@link com.aegis.core.ingestion.connectors.nvd} and
 *       {@link com.aegis.core.ingestion.connectors.osv} → {@link com.aegis.core.advisories}</li>
 *   <li>{@link com.aegis.core.ingestion.connectors.vendorstatus} → {@link com.aegis.core.incidents}</li>
 *   <li>{@link com.aegis.core.ingestion.connectors.feed} — shared, hardened RSS/Atom parsing reused by
 *       the feed-based connectors (and by {@link com.aegis.core.news}).</li>
 * </ul>
 *
 * <p>A connector may depend on the framework and on the one domain it writes to; the dependency never
 * runs the other way — the framework does not know its connectors (enforced by
 * {@code PackageStructureArchTest}).
 */
package com.aegis.core.ingestion.connectors;
