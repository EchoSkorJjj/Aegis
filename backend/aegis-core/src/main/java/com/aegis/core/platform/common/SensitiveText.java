package com.aegis.core.platform.common;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Recognisable secrets and personal contact/identity data removed at draft and AI boundaries.
 * Keep this policy aligned with ai-service's capabilities/_guardrails.py. This is not general
 * entity recognition: arbitrary names, addresses and unknown secret formats cannot be inferred.
 * Never log input, matches or replacements. Redact whole text before truncating or escaping it.
 */
public final class SensitiveText {

    public static final String REDACTED = "[REDACTED]";
    private static final List<Pattern> RULES = List.of(
            Pattern.compile("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----[\\s\\S]*?"
                    + "(?:-----END (?:[A-Z0-9]+ )*PRIVATE KEY-----|\\z)"),
            Pattern.compile("(?i)\\b(?:https?|postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis)://[^\\s/@]+:[^\\s/@]+@"),
            Pattern.compile("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9+/_.=-]+"),
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|"
                    + "(?:sk|gsk)-?[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|"
                    + "(?:AKIA|ASIA)[A-Z0-9]{16})\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"),
            Pattern.compile("(?i)\\b(?:[a-z][a-z0-9_]*_)?(?:password|passwd|pwd|secret|client[_-]?secret|api[_-]?key|"
                    + "secrets?[_-]?key|secret[_-]?access[_-]?key|private[_-]?key|"
                    + "access[_-]?token|refresh[_-]?token|token|authorization)\\b[\"']?\\s*[:=]\\s*"
                    + "(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;\"'<>]+)"),
            Pattern.compile("\\b[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@\\u200b?[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+\\b"),
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
            Pattern.compile("(?i)\\b(?:cookie|set-cookie)\\s*:\\s*[^\\r\\n]+"),
            Pattern.compile("(?i)\\b(?:phone|mobile|tel(?:ephone)?|ssn|national[_ -]?id|"
                    + "full[_ -]?name|home[_ -]?address)\\s*[:=]\\s*[^\\r\\n;]+"),
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
