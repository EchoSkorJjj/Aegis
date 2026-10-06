package com.aegis.core.actions.tracker;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.aegis.core.platform.common.SensitiveText;

/**
 * Port: file an approved risk ticket in the organization's issue tracker — Jira, through the optional
 * adapter in {@code actions.tracker.jira}. With no adapter enabled there is nowhere to file, and approving
 * a ticket says so. The issue is tracker-neutral: a title, a description of headings, paragraphs and
 * bulleted lists, a priority, a due date and labels; each adapter renders it in its own format, so no feed
 * or model text is ever interpreted as the tracker's markup.
 */
public interface IssueTracker {

    /**
     * Files the issue in the caller's organization's tracker. {@code ApiExceptions.Conflict} when the
     * organization has not connected one, with what to do about it.
     */
    FiledIssue file(IssueDraft issue);

    /** The issue as a person approved it. {@code dueDate} may be null. */
    record IssueDraft(String title, List<Block> description, Priority priority, LocalDate dueDate,
                      List<String> labels) {

        public IssueDraft {
            Objects.requireNonNull(title, "title");
            title = SensitiveText.redact(title);
            description = List.copyOf(description);
            Objects.requireNonNull(priority, "priority");
            labels = List.copyOf(SensitiveText.redact(labels));
        }
    }

    /** One block of a description: a heading, a paragraph, or a bulleted list of {@code items}. */
    record Block(Kind kind, String text, List<String> items) {

        public Block {
            Objects.requireNonNull(kind, "kind");
            text = SensitiveText.redact(text);
            items = items == null ? List.of() : List.copyOf(SensitiveText.redact(items));
        }

        public static Block heading(String text) {
            return new Block(Kind.HEADING, text, List.of());
        }

        public static Block paragraph(String text) {
            return new Block(Kind.PARAGRAPH, text, List.of());
        }

        public static Block bullets(List<String> items) {
            return new Block(Kind.BULLETS, null, items);
        }

        public enum Kind { HEADING, PARAGRAPH, BULLETS }
    }

    /** How urgent the issue is, from the impact tier; each tracker maps it to its own scale. */
    enum Priority { HIGHEST, HIGH, MEDIUM, LOW, LOWEST }

    /** The filed issue: the tracker's key for it and where to open it. */
    record FiledIssue(String key, String url) {
    }
}
