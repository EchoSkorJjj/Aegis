package com.aegis.core.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PEP 440 ordering — Python / PyPI. Covers epoch ({@code N!}), release segments, pre-releases
 * ({@code a}/{@code b}/{@code rc} and their spellings), post-releases, and dev-releases, following the
 * reference {@code packaging.version} precedence: {@code 1.0.dev1 < 1.0a1 < 1.0 < 1.0.post1}. The
 * local version ({@code +local}) does not affect ordering. Trailing release zeros are insignificant
 * ({@code 1.0 == 1.0.0}).
 */
final class Pep440Scheme implements VersionScheme {

    private static final Pattern PEP440 = Pattern.compile(
            "^\\s*v?"
                    + "(?:(\\d+)!)?"                                            // 1 epoch
                    + "(\\d+(?:\\.\\d+)*)"                                      // 2 release
                    + "(?:[-_.]?(a|b|c|rc|alpha|beta|pre|preview)[-_.]?(\\d*))?" // 3 preL 4 preN
                    + "(?:-(\\d+)|[-_.]?(post|rev|r)[-_.]?(\\d*))?"             // 5 postN(implicit) 6 postL 7 postN
                    + "(?:[-_.]?(dev)[-_.]?(\\d*))?"                            // 8 devL 9 devN
                    + "(?:\\+[a-z0-9]+(?:[-_.][a-z0-9]+)*)?"                    // local (ignored)
                    + "\\s*$",
            Pattern.CASE_INSENSITIVE);

    @Override
    public OptionalInt compare(String left, String right) {
        Optional<Pep440> a = parse(left);
        Optional<Pep440> b = parse(right);
        return a.isPresent() && b.isPresent()
                ? OptionalInt.of(a.get().compareTo(b.get()))
                : OptionalInt.empty();
    }

    private static Optional<Pep440> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = PEP440.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int epoch = matcher.group(1) == null ? 0 : Integer.parseInt(matcher.group(1));
        List<Integer> release = new ArrayList<>();
        for (String segment : matcher.group(2).split("\\.")) {
            release.add(Integer.parseInt(segment));
        }
        Integer preLetter = matcher.group(3) == null ? null : preLetterOrder(matcher.group(3));
        long preNum = number(matcher.group(4));
        boolean hasPost = matcher.group(5) != null || matcher.group(6) != null;
        long postNum = matcher.group(5) != null ? Long.parseLong(matcher.group(5)) : number(matcher.group(7));
        boolean hasDev = matcher.group(8) != null;
        long devNum = number(matcher.group(9));
        return Optional.of(new Pep440(epoch, release, preLetter, preNum, hasPost, postNum, hasDev, devNum));
    }

    private static int preLetterOrder(String letter) {
        return switch (letter.toLowerCase()) {
            case "a", "alpha" -> 0;
            case "b", "beta" -> 1;
            default -> 2; // c, rc, pre, preview
        };
    }

    private static long number(String value) {
        return value == null || value.isEmpty() ? 0 : Long.parseLong(value);
    }

    private record Pep440(
            int epoch,
            List<Integer> release,
            Integer preLetter,
            long preNum,
            boolean hasPost,
            long postNum,
            boolean hasDev,
            long devNum)
            implements Comparable<Pep440> {

        @Override
        public int compareTo(Pep440 other) {
            int comparison = Integer.compare(epoch, other.epoch);
            if (comparison != 0) {
                return comparison;
            }
            comparison = compareRelease(release, other.release);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(preRank(), other.preRank());
            if (comparison != 0) {
                return comparison;
            }
            if (preLetter != null && other.preLetter != null) {
                comparison = Integer.compare(preLetter, other.preLetter);
                if (comparison != 0) {
                    return comparison;
                }
                comparison = Long.compare(preNum, other.preNum);
                if (comparison != 0) {
                    return comparison;
                }
            }
            comparison = Long.compare(hasPost ? postNum : -1, other.hasPost ? other.postNum : -1);
            if (comparison != 0) {
                return comparison;
            }
            return Long.compare(hasDev ? devNum : Long.MAX_VALUE, other.hasDev ? other.devNum : Long.MAX_VALUE);
        }

        /** dev-only (0) sorts below a pre-release (1), which sorts below a final/post release (2). */
        private int preRank() {
            if (preLetter != null) {
                return 1;
            }
            return hasDev && !hasPost ? 0 : 2;
        }

        private static int compareRelease(List<Integer> left, List<Integer> right) {
            int length = Math.max(trimmedLength(left), trimmedLength(right));
            for (int index = 0; index < length; index++) {
                int comparison = Integer.compare(segment(left, index), segment(right, index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }

        private static int segment(List<Integer> release, int index) {
            return index < release.size() ? release.get(index) : 0;
        }

        /** Length with insignificant trailing zeros removed, so 1.0 and 1.0.0 compare equal. */
        private static int trimmedLength(List<Integer> release) {
            int length = release.size();
            while (length > 1 && release.get(length - 1) == 0) {
                length--;
            }
            return length;
        }
    }
}
