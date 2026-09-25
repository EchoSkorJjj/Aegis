package com.aegis.core.ai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The wire contract with the ai-service, mirrored by its OpenAPI schema ({@code schemas.py}). The
 * ai-service speaks snake_case and rejects unknown request fields, so every request component is named
 * explicitly; responses tolerate fields added later.
 */
public final class AiDtos {

    private AiDtos() {
    }

    /** "Assess this finding": is it real signal for this service, and why. */
    public record AssessmentRequest(
            @JsonProperty("finding_id") String findingId,
            @JsonProperty("advisory_text") String advisoryText,
            @JsonProperty("service_context") String serviceContext) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assessment(
            boolean material,
            String rationale,
            @JsonProperty("suggested_severity") String suggestedSeverity,
            List<String> citations) {
    }

    /** "Draft a response" of a given type (risk_ticket | upgrade | memo). */
    public record DraftRequest(
            @JsonProperty("finding_id") String findingId,
            @JsonProperty("draft_type") String draftType,
            String context) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Draft(@JsonProperty("draft_type") String draftType, String content, List<String> citations) {
    }
}
