package com.aegis.core.matching;

import com.aegis.core.ingestion.advisory.AffectedProduct;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionRangeMatcherTest {

    @Test
    void appliesInclusiveAndExclusiveSemverBounds() {
        AffectedProduct range = affected(null, "1.2.0", null, null, "2.0.0");

        assertThat(VersionRangeMatcher.matches("1.2.0", range)).isTrue();
        assertThat(VersionRangeMatcher.matches("1.9.9", range)).isTrue();
        assertThat(VersionRangeMatcher.matches("2.0.0", range)).isFalse();
        assertThat(VersionRangeMatcher.matches("1.1.9", range)).isFalse();
    }

    @Test
    void followsSemverPrereleaseOrderingAndIgnoresBuildMetadata() {
        AffectedProduct releaseOnly = affected(null, "1.0.0", null, "1.0.0", null);

        assertThat(VersionRangeMatcher.matches("1.0.0-rc.1", releaseOnly)).isFalse();
        assertThat(VersionRangeMatcher.matches("1.0.0+build.7", releaseOnly)).isTrue();
    }

    @Test
    void matchesConcreteVersionsAndAllVersionWildcards() {
        assertThat(VersionRangeMatcher.matches("v2.4.0", affected("2.4.0", null, null, null, null)))
                .isTrue();
        assertThat(VersionRangeMatcher.matches("release-2026", affected("release-2026", null, null, null, null)))
                .isTrue();
        assertThat(VersionRangeMatcher.matches("anything", affected(null, null, null, null, null)))
                .isTrue();
    }

    @Test
    void failsClosedWhenARangeCannotBeComparedAsSemver() {
        AffectedProduct range = affected(null, "1.0.0", null, null, "not-a-version");

        assertThat(VersionRangeMatcher.matches("1.5.0", range)).isFalse();
        assertThat(VersionRangeMatcher.matches("vendor-release", range)).isFalse();
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
