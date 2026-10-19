package com.aegis.core.ingestion.connectors.nvd;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CpeNameTest {

    @Test
    void parsesVendorProductAndVersionFromAFormattedCpe() {
        CpeName cpe = CpeName.parse("cpe:2.3:a:apache:log4j:2.14.1:*:*:*:*:*:*:*").orElseThrow();

        assertThat(cpe.part()).isEqualTo("a");
        assertThat(cpe.vendor()).isEqualTo("apache");
        assertThat(cpe.product()).isEqualTo("log4j");
        assertThat(cpe.version()).isEqualTo("2.14.1");
    }

    @Test
    void treatsAnyAndNaVersionsAsNoVersion() {
        assertThat(CpeName.parse("cpe:2.3:a:apache:log4j:*:*:*:*:*:*:*:*").orElseThrow().version()).isNull();
        assertThat(CpeName.parse("cpe:2.3:a:apache:log4j:-:*:*:*:*:*:*:*").orElseThrow().version()).isNull();
    }

    @Test
    void doesNotSplitOnEscapedColonsAndUnescapesThem() {
        CpeName cpe = CpeName.parse("cpe:2.3:a:my\\:vendor:my\\:product:1.0:*:*:*:*:*:*:*").orElseThrow();

        assertThat(cpe.vendor()).isEqualTo("my:vendor");
        assertThat(cpe.product()).isEqualTo("my:product");
        assertThat(cpe.version()).isEqualTo("1.0");
    }

    @Test
    void unescapesOtherQuotedCharacters() {
        CpeName cpe = CpeName.parse("cpe:2.3:a:vendor:node\\.js:1.0:*:*:*:*:*:*:*").orElseThrow();

        assertThat(cpe.product()).isEqualTo("node.js");
    }

    @Test
    void rejectsCpesThatNameNoConcreteVendorOrProduct() {
        // Nothing to match a registered component against, so the tuple is useless downstream.
        assertThat(CpeName.parse("cpe:2.3:a:*:*:*:*:*:*:*:*:*:*")).isEmpty();
        assertThat(CpeName.parse("cpe:2.3:a:apache:*:*:*:*:*:*:*:*:*")).isEmpty();
    }

    @Test
    void rejectsMalformedInput() {
        assertThat(CpeName.parse(null)).isEmpty();
        assertThat(CpeName.parse("")).isEmpty();
        assertThat(CpeName.parse("cpe:/a:apache:log4j:2.14.1")).isEmpty(); // CPE 2.2 URI form
        assertThat(CpeName.parse("cpe:2.3:a:apache:log4j")).isEmpty(); // too few components
        assertThat(CpeName.parse("cpe:2.3:a:apache:log4j:1:2:3:4:5:6:7:8:9")).isEmpty(); // too many
    }

    @Test
    void parseReturnsOptionalRatherThanThrowingOnUntrustedInput() {
        Optional<CpeName> parsed = CpeName.parse("not a cpe at all");

        assertThat(parsed).isEmpty();
    }
}
