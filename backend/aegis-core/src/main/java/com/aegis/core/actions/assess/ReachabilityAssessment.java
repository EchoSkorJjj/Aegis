package com.aegis.core.actions.assess;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The AI's suggestion on whether a service's code uses the vulnerable part of a package it imports (E11-5), as kept: for
 * one service, package, vulnerability and the commit whose lines it read. {@code cited} are the line ranges it rests on,
 * {@code checks} what a reviewer should verify that the code can't show, {@code searches} what the code was searched for
 * and how often it was found — never the code. Nothing scores or decides by it.
 */
public record ReachabilityAssessment(
        UUID id,
        UUID serviceId,
        String ecosystem,
        String packageName,
        String vulnerabilityId,
        String revision,
        Verdict verdict,
        String rationale,
        List<Cited> cited,
        List<String> checks,
        List<Searched> searches,
        UUID requestedBy,
        Instant assessedAt) {

    public ReachabilityAssessment {
        cited = cited == null ? List.of() : List.copyOf(cited);
        checks = checks == null ? List.of() : List.copyOf(checks);
        searches = searches == null ? List.of() : List.copyOf(searches);
    }

    /** Whether the code calls the vulnerable function: it does, it doesn't, or the lines don't show. */
    public enum Verdict { CALLED, NOT_CALLED, CANT_TELL }

    /** A name the code was searched for, and the {@code lines} in {@code files} files naming it. */
    public record Searched(String term, int lines, int files) {

        /** As stored: {@code lines:files:term}, the term last. */
        public String encode() {
            return lines + ":" + files + ":" + term;
        }

        public static Searched decode(String encoded) {
            String[] parts = encoded.split(":", 3);
            return new Searched(parts[2], Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
    }

    /** Lines {@code startLine}–{@code endLine} of {@code path}, read around the use or match on {@code line}. */
    public record Cited(String path, int startLine, int endLine, int line) {

        /** As stored: {@code start:end:line:path}, the path last so a colon in it survives. */
        public String encode() {
            return startLine + ":" + endLine + ":" + line + ":" + path;
        }

        public static Cited decode(String encoded) {
            String[] parts = encoded.split(":", 4);
            return new Cited(parts[3], Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
    }
}
