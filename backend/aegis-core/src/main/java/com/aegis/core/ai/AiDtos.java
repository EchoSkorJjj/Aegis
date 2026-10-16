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

    /**
     * Pass 1 of "does this code use the vulnerable part?" (E11-5): from the advisory, the package and what its fix
     * changed, the names to search the service's code for. Nothing from the service. Redacted here.
     */
    public record LookForRequest(
            @JsonProperty("finding_id") String findingId,
            @JsonProperty("advisory_text") String advisoryText,
            @JsonProperty("package") PackageRef pkg,
            @JsonProperty("fix_summary") String fixSummary) {
        public LookForRequest {
            advisoryText = SensitiveText.redact(advisoryText);
            fixSummary = SensitiveText.redact(fixSummary);
        }
    }

    /** The names the advisory or its fix writes, for aegis-core to search the code for; each checked by the ai-service. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LookFor(List<String> terms) {
        public LookFor {
            terms = terms == null ? List.of() : List.copyOf(terms);
        }
    }

    /**
     * Pass 2 (E11-5): the advisory, the package, what its fix changed, the search of the code for pass 1's names, and a
     * few lines of the code — where it imports the package, uses what it imports, or names a searched name — nothing
     * else from the repository. Every text field is redacted here, before it can leave Aegis.
     */
    public record AssessmentRequest(
            @JsonProperty("finding_id") String findingId,
            @JsonProperty("advisory_text") String advisoryText,
            @JsonProperty("package") PackageRef pkg,
            @JsonProperty("fix_summary") String fixSummary,
            List<Search> searches,
            List<CodeExcerpt> excerpts) {
        public AssessmentRequest {
            advisoryText = SensitiveText.redact(advisoryText);
            fixSummary = SensitiveText.redact(fixSummary);
            searches = searches == null ? List.of() : List.copyOf(searches);
            excerpts = excerpts == null ? List.of() : List.copyOf(excerpts);
        }
    }

    /** The package the code imports. */
    public record PackageRef(String ecosystem, String name) {
    }

    /** What the search of the service's code for one name found: counts, never code. */
    public record Search(
            String term,
            @JsonProperty("files_searched") int filesSearched,
            @JsonProperty("files_matched") int filesMatched,
            @JsonProperty("lines_matched") int linesMatched,
            boolean complete) {
        public Search {
            term = SensitiveText.redact(term);
        }
    }

    /**
     * Lines of the service's code from {@code startLine}; {@code id} ({@code E1}–{@code E6}) is what a citation names,
     * {@code kind} why they were sent ({@code import}, {@code use}, {@code match}) and {@code term} what a match found.
     */
    public record CodeExcerpt(
            String id,
            String path,
            @JsonProperty("start_line") int startLine,
            String text,
            String kind,
            String term) {
        public CodeExcerpt {
            path = SensitiveText.redact(path);
            text = SensitiveText.redact(text);
            term = SensitiveText.redact(term);
        }
    }

    /**
     * The AI's suggestion: {@code verdict} is {@code CALLED}, {@code NOT_CALLED} or {@code CANT_TELL}, {@code checks}
     * what a reviewer should verify that the code it read can't show, and {@code citedExcerpts} the IDs of the excerpts
     * it rests on — the model never writes citation text.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assessment(
            String verdict,
            String rationale,
            List<String> checks,
            @JsonProperty("cited_excerpts") List<String> citedExcerpts) {
        public Assessment {
            rationale = SensitiveText.redact(rationale);
            checks = checks == null ? List.of() : SensitiveText.redact(checks);
            citedExcerpts = citedExcerpts == null ? List.of() : List.copyOf(citedExcerpts);
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
