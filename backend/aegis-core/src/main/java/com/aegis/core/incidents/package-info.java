/**
 * Normalised vendor incidents — outages and degradations published by third parties the estate
 * depends on, and the second kind of "real-world change" ingestion feeds to matching (advisories
 * being the first).
 *
 * <p>Populated by {@link com.aegis.core.ingestion.connectors.vendorstatus}. Nothing here judges whether an
 * incident matters — resolving a vendor to the services that depend on it is matching's work.
 * Deterministic; no AI.
 */
package com.aegis.core.incidents;
