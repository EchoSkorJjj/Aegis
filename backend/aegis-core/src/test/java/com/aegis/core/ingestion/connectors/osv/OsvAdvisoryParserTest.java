package com.aegis.core.ingestion.connectors.osv;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.aegis.core.advisories.Advisory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OsvAdvisoryParserTest {

    private final OsvAdvisoryParser parser = new OsvAdvisoryParser(new ObjectMapper());

    @Test
    void mapsAGhsaRecordToAPurlAdvisoryWithTheCanonicalCveFromItsAliases() throws Exception {
        Advisory advisory = parser.parse(fixture("osv/ghsa-npm-axios.json"));

        assertThat(advisory.id()).isNull(); // assigned by the store on first write
        assertThat(advisory.sourceId()).isEqualTo("osv");
        assertThat(advisory.externalId()).isEqualTo("GHSA-wf5p-g6vw-rhxx");
        // The vulnerability's canonical identity is the CVE it aliases — what NVD keys on too.
        assertThat(advisory.canonicalId()).isEqualTo("CVE-2026-25639");
        assertThat(advisory.description()).isEqualTo("axios Server-Side Request Forgery");
        assertThat(advisory.lastModifiedAt()).isEqualTo(Instant.parse("2024-01-15T10:00:00Z"));
        assertThat(advisory.cvss().severity()).isEqualTo("HIGH");
        assertThat(advisory.cvss().version()).isEqualTo("3.1");
        assertThat(advisory.affected()).singleElement().satisfies(affected -> {
            assertThat(affected.isPackage()).isTrue();
            assertThat(affected.ecosystem()).isEqualTo("npm");
            assertThat(affected.packageName()).isEqualTo("axios");
            assertThat(affected.versionStartIncluding()).isNull(); // "introduced": "0"
            assertThat(affected.versionEndExcluding()).isEqualTo("1.6.0");
            assertThat(affected.versionEndIncluding()).isNull();
        });
    }

    @Test
    void mapsAPypiEcosystemRangeWithLastAffectedAndItsOwnCveId() throws Exception {
        Advisory advisory = parser.parse(fixture("osv/cve-pypi-flask.json"));

        assertThat(advisory.externalId()).isEqualTo("CVE-2025-1234");
        assertThat(advisory.canonicalId()).isEqualTo("CVE-2025-1234");
        assertThat(advisory.cvss().version()).isEqualTo("4.0");
        assertThat(advisory.cvss().severity()).isNull(); // no database_specific label
        assertThat(advisory.affected()).singleElement().satisfies(affected -> {
            assertThat(affected.ecosystem()).isEqualTo("PyPI");
            assertThat(affected.packageName()).isEqualTo("flask");
            assertThat(affected.versionStartIncluding()).isEqualTo("1.0");
            assertThat(affected.versionEndIncluding()).isEqualTo("2.2.5"); // last_affected is inclusive
            assertThat(affected.versionEndExcluding()).isNull();
        });
    }

    @Test
    void keepsTheDetailsBesideTheSummaryAndTheFixCommitsAmongTheReferences() {
        Advisory advisory = parser.parse("""
                {"id": "GHSA-wjgm-6hv5-3cvf", "modified": "2026-09-28T20:30:05Z", "aliases": ["CVE-2026-19032"],
                 "summary": "jackson-databind: Path Deserialization Missing Scheme Allowlist",
                 "details": "### Summary\\n\\nA `java.nio.file.Path` field bound from untrusted JSON...  ",
                 "references": [
                   {"type": "WEB", "url": "https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wjgm-6hv5-3cvf"},
                   {"type": "WEB", "url": "https://github.com/FasterXML/jackson-databind/commit/cc6756b61ed90b6b9227f670e0408d5d9bd48551"},
                   {"type": "FIX", "url": "https://github.com/FasterXML/jackson-databind/pull/6129"},
                   {"type": "PACKAGE", "url": "https://github.com/FasterXML/jackson-databind"}]}
                """);

        // The summary stays the description the feed shows; the details are what an assessment reads (E11-5).
        assertThat(advisory.description()).isEqualTo("jackson-databind: Path Deserialization Missing Scheme Allowlist");
        assertThat(advisory.details()).isEqualTo("### Summary\n\nA `java.nio.file.Path` field bound from untrusted JSON...");
        // GHSA marks its fix commits WEB, not FIX: every commit among the references counts.
        assertThat(advisory.fixCommits()).containsExactly(
                "https://github.com/FasterXML/jackson-databind/commit/cc6756b61ed90b6b9227f670e0408d5d9bd48551");
    }

    @Test
    void detailsThatAreAlreadyTheDescriptionAreNotKeptTwice() {
        Advisory advisory = parser.parse("{\"id\": \"PYSEC-1\", \"details\": \"Only details.\"}");
        assertThat(advisory.description()).isEqualTo("Only details.");
        assertThat(advisory.details()).isNull();
        assertThat(advisory.fixCommits()).isEmpty();
    }

    @Test
    void rejectsADocumentWithNoId() {
        assertThatThrownBy(() -> parser.parse("{\"modified\":\"2024-01-01T00:00:00Z\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String fixture(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
