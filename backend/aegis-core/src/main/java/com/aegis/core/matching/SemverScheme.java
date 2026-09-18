package com.aegis.core.matching;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SemVer 2.0 ordering — npm, Cargo, and Go, and the default used for CPE version strings. Handles
 * optional minor/patch, a leading {@code v}, pre-release precedence, and build metadata (ignored).
 */
final class SemverScheme implements VersionScheme {

    private static final Pattern SEMVER = Pattern.compile(
            "^[vV]?(0|[1-9]\\d*)(?:\\.(0|[1-9]\\d*))?(?:\\.(0|[1-9]\\d*))?"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    @Override
    public OptionalInt compare(String left, String right) {
        Optional<SemanticVersion> a = SemanticVersion.parse(left);
        Optional<SemanticVersion> b = SemanticVersion.parse(right);
        return a.isPresent() && b.isPresent()
                ? OptionalInt.of(a.get().compareTo(b.get()))
                : OptionalInt.empty();
    }

    private record SemanticVersion(
            BigInteger major, BigInteger minor, BigInteger patch, List<Identifier> preRelease)
            implements Comparable<SemanticVersion> {

        private static Optional<SemanticVersion> parse(String value) {
            if (value == null) {
                return Optional.empty();
            }
            Matcher matcher = SEMVER.matcher(value.trim());
            if (!matcher.matches()) {
                return Optional.empty();
            }
            List<Identifier> identifiers = new ArrayList<>();
            if (matcher.group(4) != null) {
                for (String token : matcher.group(4).split("\\.")) {
                    if (token.chars().allMatch(Character::isDigit)) {
                        if (token.length() > 1 && token.startsWith("0")) {
                            return Optional.empty();
                        }
                        identifiers.add(new Identifier(new BigInteger(token), null));
                    } else {
                        identifiers.add(new Identifier(null, token));
                    }
                }
            }
            return Optional.of(new SemanticVersion(
                    new BigInteger(matcher.group(1)),
                    numberOrZero(matcher.group(2)),
                    numberOrZero(matcher.group(3)),
                    List.copyOf(identifiers)));
        }

        private static BigInteger numberOrZero(String value) {
            return value == null ? BigInteger.ZERO : new BigInteger(value);
        }

        @Override
        public int compareTo(SemanticVersion other) {
            int core = major.compareTo(other.major);
            if (core == 0) {
                core = minor.compareTo(other.minor);
            }
            if (core == 0) {
                core = patch.compareTo(other.patch);
            }
            if (core != 0) {
                return core;
            }
            if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
                return Boolean.compare(preRelease.isEmpty(), other.preRelease.isEmpty());
            }
            int length = Math.min(preRelease.size(), other.preRelease.size());
            for (int index = 0; index < length; index++) {
                int identifier = preRelease.get(index).compareTo(other.preRelease.get(index));
                if (identifier != 0) {
                    return identifier;
                }
            }
            return Integer.compare(preRelease.size(), other.preRelease.size());
        }
    }

    private record Identifier(BigInteger numeric, String text) implements Comparable<Identifier> {

        @Override
        public int compareTo(Identifier other) {
            if (numeric != null && other.numeric != null) {
                return numeric.compareTo(other.numeric);
            }
            if (numeric != null) {
                return -1;
            }
            if (other.numeric != null) {
                return 1;
            }
            return text.compareTo(other.text);
        }
    }
}
