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

    private static SoftwareComponent component(String ecosystem, String name, String version) {
        return new SoftwareComponent(UUID.randomUUID(), ecosystem, name, version);
    }

    private static Advisory advisory(AffectedProduct affected) {
        return new Advisory(
                UUID.randomUUID(), "nvd", "CVE-2099-0001", "test", Instant.EPOCH, Instant.EPOCH,
                null, List.of(affected));
    }
}
