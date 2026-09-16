/**
 * The vendor status/outage connector — the second concrete feed behind the ingestion framework's
 * {@link com.aegis.core.ingestion.IngestionSource} seam, and the one that answers "is a supplier we
 * depend on currently impaired?".
 *
 * <p>{@link com.aegis.core.ingestion.vendorstatus.VendorStatusSource} captures each configured
 * vendor's status document verbatim; {@link com.aegis.core.ingestion.vendorstatus.VendorStatusNormalizer}
 * later turns those into {@link com.aegis.core.ingestion.incident.VendorIncident} records through a
 * per-format reader — Statuspage JSON, or RSS/Atom for vendors that publish no API.
 *
 * <p>Status pages are <b>untrusted external content</b>: documents are size-capped, the XML reader is
 * hardened against XXE, every field is optional, and nothing here is fed to an LLM. Deterministic;
 * no AI.
 */
package com.aegis.core.ingestion.vendorstatus;
