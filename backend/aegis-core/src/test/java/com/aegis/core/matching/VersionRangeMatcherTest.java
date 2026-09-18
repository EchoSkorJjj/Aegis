package com.aegis.core.matching;

import com.aegis.core.ingestion.advisory.AffectedProduct;
import com.aegis.core.matching.MatchingDtos.VersionMatch;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionRangeMatcherTest {

    @Test
    void inclusiveLowerAndExclusiveUpperBounds() {
        AffectedProduct range = affected(null, "1.2.0", null, null, "2.0.0"); // [1.2.0, 2.0.0)

        assertThat(evaluate("1.2.0", range)).isEqualTo(VersionMatch.IN_RANGE);
        assertThat(evaluate("1.9.9", range)).isEqualTo(VersionMatch.IN_RANGE);
        assertThat(evaluate("2.0.0", range)).isEqualTo(VersionMatch.OUT_OF_RANGE);
        assertThat(evaluate("1.1.9", range)).isEqualTo(VersionMatch.OUT_OF_RANGE);
    }

    @Test
    void exclusiveLowerAndInclusiveUpperBounds() {
        AffectedProduct range = affected(null, null, "1.0.0", "2.0.0", null); // (1.0.0, 2.0.0]

        assertThat(evaluate("1.0.0", range)).isEqualTo(VersionMatch.OUT_OF_RANGE);
        assertThat(evaluate("1.0.1", range)).isEqualTo(VersionMatch.IN_RANGE);
        assertThat(evaluate("2.0.0", range)).isEqualTo(VersionMatch.IN_RANGE);
        assertThat(evaluate("2.0.1", range)).isEqualTo(VersionMatch.OUT_OF_RANGE);
    }

    @Test
    void followsSemverPrereleaseOrderingAndIgnoresBuildMetadata() {
        AffectedProduct exact = affected(null, "1.0.0", null, "1.0.0", null); // exactly 1.0.0

        assertThat(evaluate("1.0.0-rc.1", exact)).isEqualTo(VersionMatch.OUT_OF_RANGE); // prerelease < release
        assertThat(evaluate("1.0.0+build.7", exact)).isEqualTo(VersionMatch.IN_RANGE); // build metadata ignored
    }

    @Test
    void concreteVersionPinAndAllVersionWildcard() {
        assertThat(evaluate("v2.4.0", affected("2.4.0", null, null, null, null))).isEqualTo(VersionMatch.IN_RANGE);
        assertThat(evaluate("2.4.1", affected("2.4.0", null, null, null, null))).isEqualTo(VersionMatch.OUT_OF_RANGE);
        assertThat(evaluate("1.2", affected("1.2.0", null, null, null, null))).isEqualTo(VersionMatch.IN_RANGE);
        assertThat(evaluate("anything", affected(null, null, null, null, null))).isEqualTo(VersionMatch.IN_RANGE);
    }

    @Test
    void nonSemverConcretePinComparesByString() {
        assertThat(evaluate("release-2026", affected("release-2026", null, null, null, null)))
                .isEqualTo(VersionMatch.IN_RANGE);
        assertThat(evaluate("release-2027", affected("release-2026", null, null, null, null)))
                .isEqualTo(VersionMatch.OUT_OF_RANGE);
    }

    @Test
    void unknownOrNonSemverCandidateAgainstARangeIsUndetermined() {
        AffectedProduct range = affected(null, "1.0.0", null, null, "2.0.0");

        assertThat(evaluate(null, range)).isEqualTo(VersionMatch.UNDETERMINED);
        assertThat(evaluate("  ", range)).isEqualTo(VersionMatch.UNDETERMINED);
        assertThat(evaluate("2.14.1.RELEASE", range)).isEqualTo(VersionMatch.UNDETERMINED); // Maven qualifier
        assertThat(evaluate("1!2.3", range)).isEqualTo(VersionMatch.UNDETERMINED); // PEP440 epoch
    }

    @Test
    void anUnparseableRangeBoundIsUndeterminedNotADrop() {
        AffectedProduct range = affected(null, "1.0.0", null, null, "not-a-version");

        assertThat(evaluate("1.5.0", range)).isEqualTo(VersionMatch.UNDETERMINED);
        assertThat(evaluate("vendor-release", range)).isEqualTo(VersionMatch.UNDETERMINED);
    }

    @Test
    void aDefiniteOutOfRangeWinsOverAnUnparseableBound() {
        // Below the parseable lower bound is decisive even though the upper bound is not comparable.
        AffectedProduct range = affected(null, "2.0.0", null, null, "not-a-version");

        assertThat(evaluate("1.0.0", range)).isEqualTo(VersionMatch.OUT_OF_RANGE);
    }

    private static final VersionScheme SEMVER = new SemverScheme();

    private static VersionMatch evaluate(String candidate, AffectedProduct affected) {
        return VersionRangeMatcher.evaluate(candidate, affected, SEMVER);
    }

    private static AffectedProduct affected(
            String version,
            String startIncluding,
            String startExcluding,
            String endIncluding,
            String endExcluding) {
        return new AffectedProduct(
                "cpe:2.3:a:vendor:product:*:*:*:*:*:*:*:*",
                "a",
                "vendor",
                "product",
                version,
                startIncluding,
                startExcluding,
                endIncluding,
                endExcluding);
    }
}
