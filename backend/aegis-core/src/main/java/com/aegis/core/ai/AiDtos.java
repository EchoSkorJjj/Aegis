package com.aegis.core.ai;

import java.util.List;

import com.aegis.core.platform.common.SensitiveText;
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
        public AssessmentRequest {
            advisoryText = SensitiveText.redact(advisoryText);
            serviceContext = SensitiveText.redact(serviceContext);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assessment(
            boolean material,
            String rationale,
            @JsonProperty("suggested_severity") String suggestedSeverity,
            List<String> citations) {
        public Assessment {
            rationale = SensitiveText.redact(rationale);
            citations = SensitiveText.redact(citations);
        }
    }

    /** "Draft a response" of a given type (risk_ticket | upgrade | memo). */
    public record DraftRequest(
            @JsonProperty("finding_id") String findingId,
            @JsonProperty("draft_type") String draftType,
            String context) {
        public DraftRequest {
            context = SensitiveText.redact(context);
        }
    }

    /** A risk ticket's AI-drafted narrative fields; its priority, due date and facts are aegis-core's. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TicketSections(String summary, String risk, String impact, List<String> actions) {
        public TicketSections {
            summary = SensitiveText.redact(summary);
            risk = SensitiveText.redact(risk);
            impact = SensitiveText.redact(impact);
            actions = SensitiveText.redact(actions);
        }
    }

    /** A DORA notification memo's AI-drafted sections; the memo's template supplies every fact around them. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemoSections(
            String summary, String impact, String measures, @JsonProperty("next_steps") String nextSteps) {
        public MemoSections {
            summary = SensitiveText.redact(summary);
            impact = SensitiveText.redact(impact);
            measures = SensitiveText.redact(measures);
            nextSteps = SensitiveText.redact(nextSteps);
        }
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

        public Draft {
            content = SensitiveText.redact(content);
            citations = SensitiveText.redact(citations);
        }

        /** A draft of text only, such as an upgrade's rationale. */
        public Draft(String draftType, String content, List<String> citations) {
            this(draftType, content, null, null, citations);
        }
    }
}
