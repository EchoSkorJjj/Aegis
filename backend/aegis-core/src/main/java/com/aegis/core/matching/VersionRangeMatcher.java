package com.aegis.core.matching;

import java.util.OptionalInt;

import com.aegis.core.ingestion.advisory.AffectedProduct;
import com.aegis.core.matching.MatchingDtos.VersionMatch;

/**
 * Places a component version inside an advisory's affected range using a supplied {@link VersionScheme}
 * (SemVer, PEP 440, …), so pip/RubyGems/Maven versions are compared by their own rules.
 *
 * <p>Returns a three-valued {@link VersionMatch}: {@code IN_RANGE}/{@code OUT_OF_RANGE} when the
 * version and every relevant bound compare, and {@code UNDETERMINED} when the version or a bound is
 * not a valid version in the scheme — surfaced as a review item, never a silent miss. A bound the
 * value provably violates still wins (OUT_OF_RANGE), even alongside an uncomparable one.
 */
final class VersionRangeMatcher {

    private VersionRangeMatcher() {
    }

    static VersionMatch evaluate(String candidate, AffectedProduct affected, VersionScheme scheme) {
        if (affected.affectsAllVersions()) {
            return VersionMatch.IN_RANGE;
        }
        if (candidate == null || candidate.isBlank()) {
            return VersionMatch.UNDETERMINED;
        }
        if (affected.version() != null) {
            OptionalInt comparison = scheme.compare(candidate, affected.version());
            if (comparison.isPresent()) {
                return comparison.getAsInt() == 0 ? VersionMatch.IN_RANGE : VersionMatch.OUT_OF_RANGE;
            }
            // A concrete pin either equals the exact string or it does not — that is decisive.
            return candidate.trim().equalsIgnoreCase(affected.version().trim())
                    ? VersionMatch.IN_RANGE : VersionMatch.OUT_OF_RANGE;
        }

        Bound[] bounds = {
            new Bound(affected.versionStartIncluding(), Side.LOWER, true),
            new Bound(affected.versionStartExcluding(), Side.LOWER, false),
            new Bound(affected.versionEndIncluding(), Side.UPPER, true),
            new Bound(affected.versionEndExcluding(), Side.UPPER, false),
        };
        boolean undetermined = false;
        for (Bound bound : bounds) {
            if (bound.value() == null) {
                continue;
            }
            OptionalInt comparison = scheme.compare(candidate, bound.value());
            if (comparison.isEmpty()) {
                undetermined = true;
                continue;
            }
            if (!bound.satisfiedBy(comparison.getAsInt())) {
                return VersionMatch.OUT_OF_RANGE;
            }
        }
        return undetermined ? VersionMatch.UNDETERMINED : VersionMatch.IN_RANGE;
    }

    private enum Side {
        LOWER,
        UPPER
    }

    private record Bound(String value, Side side, boolean inclusive) {
        boolean satisfiedBy(int comparison) {
            return side == Side.LOWER
                    ? (inclusive ? comparison >= 0 : comparison > 0)
                    : (inclusive ? comparison <= 0 : comparison < 0);
        }
    }
}
