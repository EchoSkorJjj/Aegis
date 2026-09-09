/**
 * Inventory &amp; map — the estate we protect: business services (with a criticality tier),
 * components (ecosystem + name + version), vendors, SBOM-derived dependencies, and ownership links.
 * Every later answer joins against this map to resolve which services and owners a change affects.
 *
 * <p>The stable core: it depends on nothing else here. First feature to implement (BUILD_PLAN.md step 1).
 */
package com.aegis.core.inventory;
