/**
 * Scoring — deterministic impact score per service and owner from severity, exposure
 * (internet-facing), reachability, and business tier, with a "why this score" breakdown and a
 * materiality threshold that down-ranks noise.
 *
 * <p>Consumes the ai-service's relevance/reachability signals but does the arithmetic itself, so the
 * ranking stays auditable and reproducible.
 */
package com.aegis.core.scoring;
