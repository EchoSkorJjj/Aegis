package com.aegis.core.matching;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * A best-effort, ecosystem-agnostic comparator for schemes without a dedicated implementation
 * (Maven, RubyGems, and anything unrecognised). It splits a version into maximal runs of digits and
 * letters, then compares run by run: numbers numerically, letters case-insensitively, a number
 * outranking a letter, and a trailing letter run (a qualifier like {@code beta}/{@code rc}) sorting
 * <em>before</em> the shorter release (so {@code 1.2.0-beta} &lt; {@code 1.2.0}).
 *
 * <p>It always yields an order for non-blank input; it does not model qualifier-specific rules (e.g.
 * Maven's {@code sp} &gt; {@code ga}), so an exact scheme should be preferred where one exists.
 */
final class TokenScheme implements VersionScheme {

    @Override
    public OptionalInt compare(String left, String right) {
        List<Token> a = tokenize(left);
        List<Token> b = tokenize(right);
        if (a.isEmpty() || b.isEmpty()) {
            return OptionalInt.empty();
        }
        int length = Math.min(a.size(), b.size());
        for (int index = 0; index < length; index++) {
            int comparison = a.get(index).compareTo(b.get(index));
            if (comparison != 0) {
                return OptionalInt.of(comparison);
            }
        }
        if (a.size() == b.size()) {
            return OptionalInt.of(0);
        }
        // The longer version's next run decides: an extra number is an increment (higher), an extra
        // letter run is a pre-release qualifier (lower).
        List<Token> longer = a.size() > b.size() ? a : b;
        int sign = a.size() > b.size() ? 1 : -1;
        Token next = longer.get(length);
        return OptionalInt.of(next.numeric() != null ? sign : -sign);
    }

    private static List<Token> tokenize(String value) {
        List<Token> tokens = new ArrayList<>();
        if (value == null) {
            return tokens;
        }
        String trimmed = value.trim();
        int index = 0;
        while (index < trimmed.length()) {
            char ch = trimmed.charAt(index);
            if (Character.isDigit(ch)) {
                int start = index;
                while (index < trimmed.length() && Character.isDigit(trimmed.charAt(index))) {
                    index++;
                }
                tokens.add(new Token(new BigInteger(trimmed.substring(start, index)), null));
            } else if (Character.isLetter(ch)) {
                int start = index;
                while (index < trimmed.length() && Character.isLetter(trimmed.charAt(index))) {
                    index++;
                }
                tokens.add(new Token(null, trimmed.substring(start, index).toLowerCase(Locale.ROOT)));
            } else {
                index++; // separators (. - _ +) are boundaries only
            }
        }
        return tokens;
    }

    private record Token(BigInteger numeric, String text) implements Comparable<Token> {

        @Override
        public int compareTo(Token other) {
            if (numeric != null && other.numeric != null) {
                return numeric.compareTo(other.numeric);
            }
            if (numeric != null) {
                return 1; // a number outranks a letter run
            }
            if (other.numeric != null) {
                return -1;
            }
            return text.compareTo(other.text);
        }
    }
}
