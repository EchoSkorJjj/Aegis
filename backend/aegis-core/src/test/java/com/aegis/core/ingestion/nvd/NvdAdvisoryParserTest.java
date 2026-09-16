package com.aegis.core.ingestion.nvd;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AffectedProduct;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class NvdAdvisoryParserTest {

    private final NvdAdvisoryParser parser = new NvdAdvisoryParser(new ObjectMapper());

    private static String fixture() throws Exception {
        return new ClassPathResource("nvd/cve-2021-44228.json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void normalisesIdentityDescriptionAndTimestamps() throws Exception {
        Advisory advisory = parser.parse(fixture());

        assertThat(advisory.id()).isNull(); // assigned by the store on first write
        assertThat(advisory.sourceId()).isEqualTo("nvd");
        assertThat(advisory.externalId()).isEqualTo("CVE-2021-44228");
        assertThat(advisory.description()).startsWith("Apache Log4j2 2.0-beta9 through 2.15.0");
        // NVD timestamps carry no offset and are UTC.
        assertThat(advisory.publishedAt()).isEqualTo(Instant.parse("2021-12-10T10:15:09.143Z"));
        assertThat(advisory.lastModifiedAt()).isEqualTo(Instant.parse("2023-11-07T03:39:04.677Z"));
    }

    @Test
    void prefersTheNewestCvssGenerationOverTheOlderOnesPublishedAlongsideIt() throws Exception {
        Advisory.Cvss cvss = parser.parse(fixture()).cvss();

        assertThat(cvss.version()).isEqualTo("3.1");
        assertThat(cvss.baseScore()).isEqualByComparingTo(new BigDecimal("10.0"));
        assertThat(cvss.severity()).isEqualTo("CRITICAL");
        assertThat(cvss.vectorString()).isEqualTo("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H");
    }

    @Test
    void normalisesAffectedProductsFromVulnerableCpeMatchesOnly() throws Exception {
        Advisory advisory = parser.parse(fixture());

        // The non-vulnerable match, the negated node, and the vendor-less wildcard are all dropped.
        assertThat(advisory.affected())
                .extracting(AffectedProduct::vendor, AffectedProduct::product, AffectedProduct::part)
                .containsExactly(
                        tuple("apache", "log4j", "a"),
                        tuple("apache", "log4j", "a"),
                        tuple("escaped:vendor", "escaped:product", "a"));
    }

    @Test
    void keepsVersionRangesAndConcreteVersionsApart() throws Exception {
        Advisory advisory = parser.parse(fixture());

        AffectedProduct ranged = advisory.affected().get(0);
        assertThat(ranged.version()).isNull();
        assertThat(ranged.versionStartIncluding()).isEqualTo("2.0");
        assertThat(ranged.versionEndExcluding()).isEqualTo("2.3.1");
        assertThat(ranged.versionStartExcluding()).isNull();
        assertThat(ranged.versionEndIncluding()).isNull();
        assertThat(ranged.affectsAllVersions()).isFalse();

        AffectedProduct exact = advisory.affected().get(1);
        assertThat(exact.version()).isEqualTo("2.15.0");
        assertThat(exact.versionStartIncluding()).isNull();
        assertThat(exact.versionEndExcluding()).isNull();
    }

    @Test
    void treatsAWildcardCpeWithNoBoundsAsAffectingEveryVersion() throws Exception {
        String payload = """
                {"id":"CVE-2024-0001","configurations":[{"nodes":[{"cpeMatch":[
                  {"vulnerable":true,"criteria":"cpe:2.3:a:vendor:product:*:*:*:*:*:*:*:*"}]}]}]}
                """;

        AffectedProduct affected = parser.parse(payload).affected().get(0);

        assertThat(affected.affectsAllVersions()).isTrue();
    }

    @Test
    void acceptsACveThatHasNotBeenScoredOrAnalysedYet() throws Exception {
        String payload = """
                {"id":"CVE-2024-0002","vulnStatus":"Awaiting Analysis",
                 "descriptions":[{"lang":"en","value":"Not yet analysed."}]}
                """;

        Advisory advisory = parser.parse(payload);

        assertThat(advisory.externalId()).isEqualTo("CVE-2024-0002");
        assertThat(advisory.cvss()).isNull();
        assertThat(advisory.affected()).isEmpty();
    }

    @Test
    void fallsBackToCvssV2WhenNoV3MetricIsPublished() throws Exception {
        String payload = """
                {"id":"CVE-2015-0001","metrics":{"cvssMetricV2":[{"type":"Primary","baseSeverity":"HIGH",
                  "cvssData":{"version":"2.0","vectorString":"AV:N/AC:M/Au:N/C:C/I:C/A:C","baseScore":9.3}}]}}
                """;

        Advisory.Cvss cvss = parser.parse(payload).cvss();

        assertThat(cvss.version()).isEqualTo("2.0");
        assertThat(cvss.baseScore()).isEqualByComparingTo(new BigDecimal("9.3"));
        // v2 carries baseSeverity on the metric rather than inside cvssData.
        assertThat(cvss.severity()).isEqualTo("HIGH");
    }

    @Test
    void ignoresUnknownFieldsSoNvdCanExtendItsSchema() throws Exception {
        String payload = """
                {"id":"CVE-2024-0003","brandNewNvdField":{"nested":true},"descriptions":[]}
                """;

        assertThat(parser.parse(payload).externalId()).isEqualTo("CVE-2024-0003");
    }

    @Test
    void rejectsADocumentWithNoCveId() {
        assertThatThrownBy(() -> parser.parse("{\"descriptions\":[]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no CVE id");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{not json"))
                .isInstanceOf(JacksonException.class);
    }
}
