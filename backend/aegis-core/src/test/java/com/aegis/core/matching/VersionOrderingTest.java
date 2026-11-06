package com.aegis.core.matching;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pre-releases and qualifiers: the orderings a vulnerable range turns on, where a wrong answer calls a fixed version
 * vulnerable or the reverse.
 */
class VersionOrderingTest {

    private static void assertAscending(VersionScheme scheme, List<String> versions) {
        for (int low = 0; low < versions.size(); low++) {
            for (int high = low + 1; high < versions.size(); high++) {
                String left = versions.get(low);
                String right = versions.get(high);
                assertThat(scheme.compare(left, right).orElseThrow()).as("%s < %s", left, right).isNegative();
                assertThat(scheme.compare(right, left).orElseThrow()).as("%s > %s", right, left).isPositive();
            }
        }
    }

    @Test
    void semverOrdersPreReleasesAsTheSpecDoes() {
        // The worked example of semver.org, section 11.
        assertAscending(new SemverScheme(), List.of(
                "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11",
                "1.0.0-rc.1", "1.0.0", "1.0.1", "1.10.0", "2.0.0"));
    }

    @Test
    void semverIgnoresBuildMetadata() {
        assertThat(new SemverScheme().compare("1.0.0+build.1", "1.0.0+build.2").orElseThrow()).isZero();
    }

    @Test
    void semverDeclinesWhatIsntAVersion() {
        assertThat(new SemverScheme().compare("not-a-version", "1.0.0")).isEmpty();
    }

    @Test
    void tokensCompareNumbersNumericallyAndTreatAQualifierAsEarlier() {
        assertAscending(new TokenScheme(), List.of("1.2", "1.2.1", "1.2.9", "1.2.10", "1.10"));
        assertAscending(new TokenScheme(), List.of("2.0.rc1", "2.0"));
        assertThat(new TokenScheme().compare("1.0-ALPHA", "1.0-alpha").orElseThrow()).isZero();
    }

    @Test
    void tokensDeclineAnEmptyVersion() {
        assertThat(new TokenScheme().compare("", "1.0")).isEmpty();
        assertThat(new TokenScheme().compare("1.0", null)).isEmpty();
    }
}
