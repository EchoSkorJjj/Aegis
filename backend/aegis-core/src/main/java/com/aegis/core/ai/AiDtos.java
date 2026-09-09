package com.aegis.core.ai;

import java.util.List;

/**
 * The wire contract with the ai-service, mirrored by its OpenAPI schema. Fields are intentionally
 * minimal for the scaffold and will grow with the feature (BUILD_PLAN.md step 7).
 *
 * <p>Note: the ai-service uses snake_case JSON; when {@link HttpAiClient} is implemented, configure
 * the client's {@code ObjectMapper} with {@code SNAKE_CASE} (or annotate these records) so the
 * mapping stays aligned.
 */
public final class AiDtos {

    private AiDtos() {
    }

    /** "Assess this finding": is it real signal for this service, and why. */
    public record AssessmentRequest(String findingId, String advisoryText, String serviceContext) {
    }

    public record Assessment(boolean material, String rationale, String suggestedSeverity, List<String> citations) {
    }

    /** "Draft a response" of a given type (risk_ticket | upgrade | memo). */
    public record DraftRequest(String findingId, String draftType, String context) {
    }

    public record Draft(String draftType, String content, List<String> citations) {
    }
}
