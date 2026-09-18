package com.aegis.core.matching;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AffectedProduct;
import com.aegis.core.inventory.domain.SoftwareComponent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisoryComponentMatcherTest {

    private final AdvisoryComponentMatcher matcher = new AdvisoryComponentMatcher();

    @Test
    void matchesAnExactPackageOnlyInsideTheAffectedRange() {
        SoftwareComponent affected = component("npm", "@acme/widget", "1.4.0");
        SoftwareComponent fixed = component("npm", "@acme/widget", "2.0.0");
        SoftwareComponent other = component("npm", "@other/widget", "1.4.0");
        Advisory advisory = advisory(new AffectedProduct(
                "cpe:2.3:a:acme:widget:*:*:*:*:*:*:*:*",
                "a", "acme", "widget", null, "1.0.0", null, null, "2.0.0"));

        assertThat(matcher.match(advisory, List.of(affected, fixed, other)))
                .extracting(match -> match.component().getId())
                .containsExactly(affected.getId());
    }

    @Test
    void permitsVendorQualifiedCpeAliasButRejectsTheSamePrefixFromAnotherVendor() {
        SoftwareComponent apache = component("maven", "org.apache.logging.log4j:log4j-core", "2.14.1");
        SoftwareComponent unrelated = component("maven", "com.example:log4j-helper", "2.14.1");
        Advisory advisory = advisory(new AffectedProduct(
                "cpe:2.3:a:apache:log4j:*:*:*:*:*:*:*:*",
                "a", "apache", "log4j", null, null, null, null, "2.15.0"));

        assertThat(matcher.match(advisory, List.of(apache, unrelated)))
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.component().getId()).isEqualTo(apache.getId());
                    assertThat(match.identityMatch())
                            .isEqualTo(MatchingDtos.IdentityMatch.CPE_VENDOR_PRODUCT_ALIAS);
                });
    }

    @Test
    void ignoresNonApplicationCpes() {
        SoftwareComponent component = component("npm", "widget", "1.0.0");
        Advisory advisory = advisory(new AffectedProduct(
                "cpe:2.3:o:acme:widget:*:*:*:*:*:*:*:*",
                "o", "acme", "widget", null, null, null, null, null));

        assertThat(matcher.match(advisory, List.of(component))).isEmpty();
    }

    @Test
    void confirmsAFullyQualifiedInRangeMatch() {
        SoftwareComponent apache = component("maven", "org.apache.logging.log4j:log4j-core", "2.14.1");
        Advisory advisory = advisory(new AffectedProduct(
                "cpe:2.3:a:apache:log4j-core:*:*:*:*:*:*:*:*",
                "a", "apache", "log4j-core", null, null, null, null, "2.15.0"));

        assertThat(matcher.match(advisory, List.of(apache))).singleElement().satisfies(match -> {
            assertThat(match.identityMatch()).isEqualTo(MatchingDtos.IdentityMatch.EXACT_PACKAGE);
            assertThat(match.versionMatch()).isEqualTo(MatchingDtos.VersionMatch.IN_RANGE);
            assertThat(match.confidence()).isEqualTo(MatchingDtos.MatchConfidence.CONFIRMED);
        });
    }

    @Test
    void flagsABareNameMatchAsPossibleBecauseItCanCollideAcrossEcosystems() {
        // The same short product name exists in two ecosystems and the CPE cannot say which. Both are
        // surfaced, but only as POSSIBLE (a review item) — a false positive is never asserted as fact.
        SoftwareComponent npm = component("npm", "requests", "2.0.0");
        SoftwareComponent pypi = component("pypi", "requests", "2.0.0");
        Advisory advisory = advisory(new AffectedProduct(
                "cpe:2.3:a:python:requests:*:*:*:*:*:*:*:*",
                "a", "python", "requests", null, null, null, null, "3.0.0"));

        assertThat(matcher.match(advisory, List.of(npm, pypi)))
                .hasSize(2)
                .allSatisfy(match -> {
                    assertThat(match.identityMatch()).isEqualTo(MatchingDtos.IdentityMatch.AMBIGUOUS_NAME);
                    assertThat(match.confidence()).isEqualTo(MatchingDtos.MatchConfidence.POSSIBLE);
                });
    }

    @Test
    void keepsAnUnknownVersionMatchAsPossibleRatherThanDroppingIt() {
        // A non-SemVer version against a range cannot be placed → UNDETERMINED → still surfaced,
        // as POSSIBLE. For a security tool a version we cannot compare is a review item, not a miss.
        SoftwareComponent maven = component("maven", "org.apache.logging.log4j:log4j-core", "2.14.1.RELEASE");
        Advisory advisory = advisory(new AffectedProduct(
                "cpe:2.3:a:apache:log4j-core:*:*:*:*:*:*:*:*",
                "a", "apache", "log4j-core", null, null, null, null, "2.15.0"));

        assertThat(matcher.match(advisory, List.of(maven))).singleElement().satisfies(match -> {
            assertThat(match.versionMatch()).isEqualTo(MatchingDtos.VersionMatch.UNDETERMINED);
            assertThat(match.confidence()).isEqualTo(MatchingDtos.MatchConfidence.POSSIBLE);
        });
    }

    @Test
    void excludesAComponentProvablyOutsideTheAffectedRange() {
        SoftwareComponent fixed = component("maven", "org.apache.logging.log4j:log4j-core", "2.17.1");
        Advisory advisory = advisory(new AffectedProduct(
                "cpe:2.3:a:apache:log4j-core:*:*:*:*:*:*:*:*",
                "a", "apache", "log4j-core", null, null, null, null, "2.15.0"));

        assertThat(matcher.match(advisory, List.of(fixed))).isEmpty();
    }

    @Test
    void confirmsAPurlMatchInTheSameEcosystem() {
        SoftwareComponent axios = component("npm", "axios", "1.5.0");
        AffectedProduct affected = AffectedProduct.ofPackage("npm", "axios", null, null, null, null, "1.6.0");

        assertThat(matcher.match(List.of(affected), List.of(axios))).singleElement().satisfies(match -> {
            assertThat(match.identityMatch()).isEqualTo(MatchingDtos.IdentityMatch.EXACT_PACKAGE);
            assertThat(match.versionMatch()).isEqualTo(MatchingDtos.VersionMatch.IN_RANGE);
            assertThat(match.confidence()).isEqualTo(MatchingDtos.MatchConfidence.CONFIRMED);
        });
    }

    @Test
    void rejectsAPurlMatchInADifferentEcosystem() {
        // Same short name, different registry: an npm advisory must not touch the PyPI component.
        SoftwareComponent pypi = component("pypi", "requests", "2.0.0");
        AffectedProduct npmAffected = AffectedProduct.ofPackage("npm", "requests", null, null, null, null, "3.0.0");

        assertThat(matcher.match(List.of(npmAffected), List.of(pypi))).isEmpty();
    }

    @Test
    void appliesPep440OrderingForPypiPackages() {
        // 2.0.0rc1 is a pre-release, so it is below (and inside [.., 2.0.0)); 2.0.0 itself is excluded.
        SoftwareComponent rc = component("PyPI", "flask", "2.0.0rc1");
        SoftwareComponent released = component("PyPI", "flask", "2.0.0");
        AffectedProduct affected = AffectedProduct.ofPackage("PyPI", "flask", null, null, null, null, "2.0.0");

        assertThat(matcher.match(List.of(affected), List.of(rc, released)))
                .extracting(match -> match.component().getId())
                .containsExactly(rc.getId());
    }

    private static SoftwareComponent component(String ecosystem, String name, String version) {
        return new SoftwareComponent(UUID.randomUUID(), ecosystem, name, version);
    }

    private static Advisory advisory(AffectedProduct affected) {
        return new Advisory(
                UUID.randomUUID(), "nvd", "CVE-2099-0001", "test", Instant.EPOCH, Instant.EPOCH,
                null, List.of(affected));
    }
}
