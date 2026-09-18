/**
 * Scoring — the deterministic impact score per service and owner (Epic 7).
 *
 * <p>{@code score = severity x exposure x criticality x 100}: the advisory's published CVSS severity,
 * the exposure its owner declared on the service, and the service's business tier. Matching has
 * already established <em>whether</em> a change reaches a service and with what confidence; scoring
 * only says <em>how much it matters</em>, banding the result into an {@code ImpactTier} and marking
 * anything under the materiality threshold as noise.
 *
 * <p>The arithmetic ({@code ImpactScorer}) is pure and its weighting ({@code ScoringWeights}) is data,
 * so a ranking can always be reproduced and argued with. Nothing here interprets advisory prose or
 * calls the ai-service: when the AI lands it will supply relevance and reachability opinions as
 * further inputs, and this module will still do the arithmetic itself.
 *
 * <p>Like matching, scores are derived per request rather than stored — a score is only true of the
 * inventory and the CVSS data behind it at the moment it was computed.
 */
package com.aegis.core.scoring;
