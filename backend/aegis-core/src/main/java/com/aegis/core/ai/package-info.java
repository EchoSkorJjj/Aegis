/**
 * AI port — the boundary between the Java core and the Python <b>ai-service</b>. The AI logic
 * (assess + draft + RAG) does not live here; this package holds the <i>seam</i>: the
 * {@link com.aegis.core.ai.AiAssessor} / {@link com.aegis.core.ai.AiDrafter} interfaces the domain
 * depends on, the request/response DTOs (the wire contract, mirrored by the ai-service's OpenAPI),
 * and one HTTP-client implementation ({@link com.aegis.core.ai.HttpAiClient}).
 *
 * <p>Keeping the domain behind these interfaces is the future-proofing: the transport (REST now,
 * a queue later), the LLM provider, and the ai-service's internals can all change without touching
 * matching/scoring/actions. The ai-service is internal-only and is called with a service token, not
 * a user JWT.
 */
package com.aegis.core.ai;
