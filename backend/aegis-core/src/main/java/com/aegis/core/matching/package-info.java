/**
 * Matching — resolves an advisory's affected package and <b>version range</b> to registered
 * components (exact, rule-based; no AI), then traverses the inventory map to the impacted services
 * and owners, returning an explicit evidence chain per affected service. Matching is derived from
 * the current signal plus the caller's current org-scoped inventory; scoring and persisted findings
 * remain later pipeline stages.
 */
package com.aegis.core.matching;
