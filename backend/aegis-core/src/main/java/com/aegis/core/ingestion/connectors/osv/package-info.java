/**
 * The OSV connector: ecosystem-native vulnerability advisories (npm, PyPI, crates.io, RubyGems, Go,
 * Maven, …) from <a href="https://osv.dev">osv.dev</a>, which aggregates GHSA, PyPA, RustSec, RubySec
 * and Go into one purl-keyed schema with cross-source aliases.
 *
 * <p>Structure mirrors the NVD connector: {@link com.aegis.core.ingestion.connectors.osv.OsvSource} downloads the
 * per-ecosystem bulk export and captures raw events; {@link com.aegis.core.ingestion.connectors.osv.OsvNormalizer}
 * drains them through {@link com.aegis.core.ingestion.connectors.osv.OsvAdvisoryParser} into the shared
 * {@code advisories} store, as purl-shaped affected products carrying a canonical (CVE) id so a
 * vulnerability reported by both NVD and OSV deduplicates.
 */
package com.aegis.core.ingestion.connectors.osv;
