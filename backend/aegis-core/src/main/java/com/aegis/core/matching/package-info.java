/**
 * Matching — resolves an advisory's affected package and <b>version range</b> to registered
 * components (exact, rule-based; no AI), then traverses the inventory map to the impacted services
 * and owners, creating a Finding per (advisory, service).
 */
package com.aegis.core.matching;
