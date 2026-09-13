package com.aegis.core.inventory.sbom;

import com.aegis.core.common.ApiExceptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageUrlTest {

    @Test
    void mavenNamesUseGroupColonArtifact() {
        PackageUrl purl = PackageUrl.parse("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1?type=jar");

        assertThat(purl).isEqualTo(new PackageUrl("maven", "org.apache.logging.log4j", "log4j-core", "2.14.1"));
        assertThat(purl.packageName()).isEqualTo("org.apache.logging.log4j:log4j-core");
    }

    @Test
    void npmScopesDecodeWhetherEncodedOrRaw() {
        PackageUrl encoded = PackageUrl.parse("pkg:npm/%40angular/core@16.2.0");
        PackageUrl raw = PackageUrl.parse("pkg:npm/@angular/core@16.2.0");

        assertThat(encoded).isEqualTo(raw);
        assertThat(encoded.packageName()).isEqualTo("@angular/core");
        assertThat(encoded.version()).isEqualTo("16.2.0");
    }

    @Test
    void goNamespacesKeepEverySegment() {
        PackageUrl purl = PackageUrl.parse("pkg:golang/github.com/gorilla/mux@v1.8.0#subpackage");

        assertThat(purl.namespace()).isEqualTo("github.com/gorilla");
        assertThat(purl.packageName()).isEqualTo("github.com/gorilla/mux");
        assertThat(purl.version()).isEqualTo("v1.8.0");
    }

    @Test
    void typeIsCaseInsensitiveAndVersionIsOptional() {
        PackageUrl purl = PackageUrl.parse("PKG:PyPI/requests");

        assertThat(purl).isEqualTo(new PackageUrl("pypi", null, "requests", null));
    }

    @Test
    void plusIsLiteralAndPercentEncodingIsDecoded() {
        PackageUrl purl = PackageUrl.parse("pkg:generic/lib%20x@1.0+build.5");

        assertThat(purl.name()).isEqualTo("lib x");
        assertThat(purl.version()).isEqualTo("1.0+build.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"lodash@4.17.20", "pkg:", "pkg:npm", "pkg:/lodash@1", "pkg:npm/@1.0", "pkg:1npm/x@1",
            "pkg:npm/bad%zzname@1"})
    void rejectsMalformedPackageUrls(String value) {
        assertThatThrownBy(() -> PackageUrl.parse(value))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageStartingWith("Invalid package URL");
    }

    @Test
    void truncatesVeryLongValuesInErrors() {
        String value = "nope:" + "x".repeat(500);

        assertThatThrownBy(() -> PackageUrl.parse(value))
                .hasMessageEndingWith("…")
                .satisfies(ex -> assertThat(ex.getMessage()).hasSizeLessThan(150));
    }
}
