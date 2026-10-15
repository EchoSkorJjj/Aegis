/**
 * Scoring — the deterministic impact score per service and owner (Epic 7).
 *
 * <p>{@code score = severity x exposure x reachability x criticality x 100}: the advisory's published
 * CVSS severity, the exposure declared on the service, whether the service's code reaches the vulnerable
 * package ({@code inventory.Reachability}, E11-4), and the service's business tier. Matching has
 * already established <em>whether</em> a change reaches a service and with what confidence; scoring
 * only says <em>how much it matters</em>, banding the result into an {@code ImpactTier} and marking
 * anything under the materiality threshold as noise.
 *
 * <p>The arithmetic ({@code ImpactScorer}) is pure and its weighting ({@code ScoringProperties}) is configuration,
 * so a ranking can always be reproduced and argued with. Nothing here interprets advisory prose or
 * calls the ai-service: reachability is read from the code statically, and the AI's opinion on whether a
 * vulnerable function is called (E11-5) is a suggestion beside the score, never a term in it.
 *
 * <p>Like matching, scores are derived per request rather than stored — a score is only true of the
 * inventory and the CVSS data behind it at the moment it was computed.
 */
package com.aegis.core.scoring;
