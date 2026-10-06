package com.aegis.core.platform.common;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Recognisable secrets and personal contact/identity data removed at draft and AI boundaries.
 * Keep this policy aligned with ai-service's capabilities/_guardrails.py. This is not general
 * entity recognition: arbitrary names, addresses and unknown secret formats cannot be inferred.
 * Each rule matches the shape of a credential or contact detail, not the words around one: advisories
 * name packages as {@code lodash@4.17.21} and describe "Basic authentication" or "Bearer tokens", and
 * that text must reach the drafts intact. Each rule redacts the value only, never the rest of the line.
 * Never log input, matches or replacements. Redact whole text before truncating or escaping it.
 */
public final class SensitiveText {

    public static final String REDACTED = "[REDACTED]";
    private static final String SECRET_KEY = "(?:[a-z][a-z0-9_]*_)?(?:password|passwd|pwd|secret|client[_-]?secret|"
            + "api[_-]?key|secrets?[_-]?key|secret[_-]?access[_-]?key|private[_-]?key|"
            + "access[_-]?token|refresh[_-]?token|token|authorization)";
    private static final List<Pattern> RULES = List.of(
            Pattern.compile("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----[\\s\\S]*?"
                    + "(?:-----END (?:[A-Z0-9]+ )*PRIVATE KEY-----|\\z)"),
            Pattern.compile("(?i)\\b(?:https?|postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis)://[^\\s/@]+:[^\\s/@]+@"),
            // Base64 credentials: a lowercase English word ("Basic authentication") has no digit, symbol or
            // capital after its first letter.
            Pattern.compile("\\b(?i:basic)\\s+(?=[A-Za-z0-9+/]{8,}={0,2}(?![A-Za-z0-9+/=]))"
                    + "[A-Za-z0-9+/][A-Za-z0-9+/]*?[0-9A-Z+/][A-Za-z0-9+/]*={0,2}"),
            Pattern.compile("\\b(?i:bearer)\\s+(?=[A-Za-z0-9._~+/-]{16,}=*(?![A-Za-z0-9._~+/=-]))"
                    + "(?=[A-Za-z0-9._~+/-]*[0-9._~+/-])[A-Za-z0-9._~+/-]+=*"),
            // Provider keys are random, so they contain a digit; "skip_…" or a service called "sk-payments-…" doesn't.
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|"
                    + "(?:sk|gsk)[-_](?=[A-Za-z0-9_-]*[0-9])[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|"
                    + "(?:AKIA|ASIA)[A-Z0-9]{16})\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"),
            // An assignment always holds a value; after a colon, prose ("CSRF token: the server…") must not match,
            // so the value has to be quoted, contain a digit or symbol, or be long.
            Pattern.compile("(?i)\\b" + SECRET_KEY + "\\b[\"']?\\s*=\\s*(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;\"'<>]+)"),
            Pattern.compile("(?i)\\b" + SECRET_KEY + "\\b[\"']?\\s*:\\s*(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|"
                    + "(?=[^\\s,;\"'<>]*[0-9_+/=@#$%^&*!?~-])[^\\s,;\"'<>]+|[^\\s,;\"'<>]{16,})"),
            // An email's domain ends in a letter TLD and never starts like a version (lodash@4.17.21,
            // pkg:maven/…/spring-core@5.3.20.RELEASE).
            Pattern.compile("\\b[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@\\u200b?(?![0-9]+\\.[0-9]+)"
                    + "[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}\\b"),
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
            Pattern.compile("(?i)\\b(?:cookie|set-cookie)\\s*:\\s*[^\\s=;,]+=[^\\r\\n]*"),
            Pattern.compile("(?i)\\b(?:phone|mobile|tel(?:ephone)?)\\s*[:=]\\s*\\+?[0-9(][0-9 ().-]{5,}[0-9]"),
            Pattern.compile("(?i)\\b(?:ssn|national[_ -]?id)\\s*[:=]\\s*[A-Za-z0-9][A-Za-z0-9-]*(?: [0-9][A-Za-z0-9-]*)*"),
            Pattern.compile("(?i)\\bfull[_ -]?name\\s*[:=]\\s*[^\\r\\n;,.]+"),
            Pattern.compile("(?i)\\bhome[_ -]?address\\s*[:=]\\s*[^\\r\\n;]+"),
            Pattern.compile("(?<![\\w])\\+\\d{1,3}[ -](?:\\d[ ()-]*){7,14}\\d(?![\\w])"));

    private SensitiveText() {
    }

    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        for (Pattern rule : RULES) {
            text = rule.matcher(text).replaceAll(REDACTED);
        }
        return text;
    }

    public static List<String> redact(List<String> texts) {
        return texts == null ? null : texts.stream().map(SensitiveText::redact).toList();
    }
}
