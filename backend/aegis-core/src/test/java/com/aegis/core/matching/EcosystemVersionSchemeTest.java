package com.aegis.core.matching;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EcosystemVersionSchemeTest {

    @Test
    void pep440OrdersEpochReleasePrereleasePostAndDev() {
        VersionScheme pep = new Pep440Scheme();

        assertOrdered(pep, "1.0.dev1", "1.0a1", "1.0b1", "1.0rc1", "1.0", "1.0.post1"); // PEP 440 precedence
        assertThat(sign(pep, "1.0", "1.0.0")).isZero();       // trailing release zeros are insignificant
        assertThat(sign(pep, "1!1.0", "2.0")).isPositive();   // an epoch outranks the release
        assertThat(sign(pep, "1.2.10", "1.2.9")).isPositive(); // numeric, not lexical
        assertThat(pep.compare("not-a-version", "1.0")).isEmpty();
    }

    @Test
    void tokenSchemeIsNumericAwareAndTreatsTrailingQualifiersAsPrereleases() {
        VersionScheme token = new TokenScheme();

        assertThat(sign(token, "2.14.1.RELEASE", "2.15.0")).isNegative(); // 14 < 15 decides
        assertThat(sign(token, "1.0.0", "1.0.0.beta")).isPositive();      // release outranks a qualifier
        assertThat(sign(token, "1.2.10", "1.2.9")).isPositive();          // numeric, not lexical
        assertThat(sign(token, "2.0.0", "2.0.0")).isZero();
    }

    @Test
    void selectsTheSchemeByEcosystem() {
        assertThat(Ecosystems.versionScheme("npm")).isInstanceOf(SemverScheme.class);
        assertThat(Ecosystems.versionScheme("crates.io")).isInstanceOf(SemverScheme.class);
        assertThat(Ecosystems.versionScheme("Go")).isInstanceOf(SemverScheme.class);
        assertThat(Ecosystems.versionScheme("PyPI")).isInstanceOf(Pep440Scheme.class);
        assertThat(Ecosystems.versionScheme("RubyGems")).isInstanceOf(TokenScheme.class);
        assertThat(Ecosystems.versionScheme("Maven")).isInstanceOf(TokenScheme.class);
    }

    @Test
    void reconcilesOsvAndPurlEcosystemNames() {
        assertThat(Ecosystems.sameEcosystem("PyPI", "pypi")).isTrue();
        assertThat(Ecosystems.sameEcosystem("crates.io", "cargo")).isTrue();
        assertThat(Ecosystems.sameEcosystem("RubyGems", "gem")).isTrue();
        assertThat(Ecosystems.sameEcosystem("Go", "golang")).isTrue();
        assertThat(Ecosystems.sameEcosystem("npm", "PyPI")).isFalse();
    }

    private static void assertOrdered(VersionScheme scheme, String... ascending) {
        for (int index = 0; index + 1 < ascending.length; index++) {
            assertThat(sign(scheme, ascending[index], ascending[index + 1]))
                    .as("%s < %s", ascending[index], ascending[index + 1])
                    .isNegative();
        }
    }

    private static int sign(VersionScheme scheme, String left, String right) {
        OptionalInt comparison = scheme.compare(left, right);
        assertThat(comparison).as("%s vs %s should be comparable", left, right).isPresent();
        return Integer.signum(comparison.getAsInt());
    }
}
