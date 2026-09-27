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

    /** A risk ticket's AI-drafted narrative fields; its priority, due date and facts are aegis-core's. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TicketSections(String summary, String risk, String impact, List<String> actions) {
    }

    /** A DORA notification memo's AI-drafted sections; the memo's template supplies every fact around them. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemoSections(
            String summary, String impact, String measures, @JsonProperty("next_steps") String nextSteps) {
    }

    /**
     * A draft: {@code content} is the whole of it as readable text; a risk ticket also carries
     * {@code ticket} and a memo {@code memo}, the typed sections aegis-core assembles its document from.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Draft(
            @JsonProperty("draft_type") String draftType,
            String content,
            TicketSections ticket,
            MemoSections memo,
            List<String> citations) {

        /** A draft of text only, such as an upgrade's rationale. */
        public Draft(String draftType, String content, List<String> citations) {
            this(draftType, content, null, null, citations);
        }
    }
}
