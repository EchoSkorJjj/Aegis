package com.aegis.core.audit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.audit.DecisionRecord.Action;
import com.aegis.core.audit.DecisionRecord.Subject;
import com.aegis.core.audit.EvidenceExport.Evidence;
import com.aegis.core.audit.EvidenceExport.Format;
import com.aegis.core.audit.EvidenceExport.People;
import com.aegis.core.audit.EvidenceExport.Records;
import com.aegis.core.platform.identity.DirectoryUser;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PageMode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evidence writers on their own, no database: the CSV quotes every field and makes formulas inert, its bytes (and
 * so its digest) depend only on the evidence, people are named without email addresses, and the PDF prints what its
 * fonts can, survives what they can't, and carries its CSV attached. That an export holds exactly the trail's records
 * is {@link EvidenceExportIntegrationTest}'s and {@link EvidenceExportCorrectnessIntegrationTest}'s.
 */
class EvidenceWritersTest {

    private static final UUID SERVICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ACTOR = UUID.fromString("b2000000-0000-0000-0000-0000000000b2");
    private static final UUID NAMELESS = UUID.fromString("c3000000-0000-0000-0000-0000000000c3");
    private static final UUID FORMER = UUID.fromString("d4000000-0000-0000-0000-0000000000d4");
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-01T00:00:00Z");
    private static final People PEOPLE = People.of(List.of(
            new DirectoryUser(ACTOR, "alice@example.com", "Alice Analyst", null, true),
            new DirectoryUser(NAMELESS, "nameless@example.com", null, null, true)));

    @TempDir
    Path temp;

    @Test
    void everyFieldIsQuotedItsQuotesDoubled() {
        assertThat(EvidenceCsv.field("plain")).isEqualTo("\"plain\"");
        assertThat(EvidenceCsv.field("say \"hi\", then go")).isEqualTo("\"say \"\"hi\"\", then go\"");
        assertThat(EvidenceCsv.field("two\r\nlines")).isEqualTo("\"two\r\nlines\"");
        assertThat(EvidenceCsv.field("")).isEqualTo("\"\"");
    }

    @Test
    void aFieldASpreadsheetWouldRunIsMadeInert() {
        for (String formula : List.of("=HYPERLINK(\"https://evil.example\")", "+1", "-2+3", "@SUM(A1)", "\tx", "\rx")) {
            assertThat(EvidenceCsv.field(formula)).startsWith("\"'");
        }
        assertThat(EvidenceCsv.field("a=b")).isEqualTo("\"a=b\"");
    }

    @Test
    void theCsvHasAHeaderARowPerRecordAndTheSameBytesForTheSameEvidence() throws IOException {
        List<DecisionRecord> records = List.of(
                record(1, Action.DRAFTED, null, "{\"status\":\"PROPOSED\"}"),
                record(2, Action.DISMISSED, "=cmd|' /C calc'!A0", "{\"status\":\"DISMISSED\"}"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        EvidenceCsv.Written written = EvidenceCsv.write(Records.of(records), PEOPLE, out);
        byte[] csv = out.toByteArray();
        String text = new String(csv, StandardCharsets.UTF_8);

        assertThat(text).startsWith("﻿\"record_id\",\"sequence\",\"occurred_at\"");
        assertThat(text.split("\r\n")).hasSize(3);
        assertThat(text).contains("\"Alice Analyst\"", "\"'=cmd|' /C calc'!A0\"", "\"{\"\"status\"\":\"\"DISMISSED\"\"}\"");
        assertThat(written.rows()).isEqualTo(2);
        assertThat(written.sha256()).hasSize(64).isEqualTo(EvidenceCsv.sha256(csv));
        assertThat(written.lastServiceName()).isEqualTo("Payments API");
        assertThat(csv(records)).isEqualTo(csv);
    }

    @Test
    void peopleAreNamedWithoutTheirEmailAddresses() throws IOException {
        assertThat(PEOPLE.nameOf(ACTOR)).isEqualTo("Alice Analyst");
        // A member without a display name, and someone no longer a member, by the start of their id.
        assertThat(PEOPLE.nameOf(NAMELESS)).isEqualTo("Member c3000000");
        assertThat(PEOPLE.nameOf(FORMER)).isEqualTo("Former member d4000000");

        List<DecisionRecord> records = List.of(record(1, Action.DRAFTED, null, "{}", NAMELESS),
                record(2, Action.APPROVED, null, "{}", FORMER));
        Path csv = csvFile(records);
        String text = pdfText(evidence(2), records, csv);
        assertThat(Files.readString(csv)).contains("\"Member c3000000\"", "\"Former member d4000000\"")
                .doesNotContain("@example.com");
        assertThat(text).contains("By Member c3000000", "By Former member d4000000").doesNotContain("@example.com");
    }

    @Test
    void characterOutsideTheStandardFontsArePrintedAsQuestionMarks() {
        PDType1Font helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        assertThat(EvidencePdf.printable("Zürich – café", helvetica)).isEqualTo("Zürich – café");
        assertThat(EvidencePdf.printable("東京 🚀", helvetica)).isEqualTo("?? ?");
        assertThat(EvidencePdf.printable("a\tb\u0007c", helvetica)).isEqualTo("a    b c");
    }

    @Test
    void thePdfShowsEachRecordTheCoverAndTheSnapshots() throws IOException {
        String longWord = "x".repeat(400);
        List<DecisionRecord> records = List.of(
                record(41, Action.OVERRIDDEN, "Behind the WAF in 東京; " + longWord,
                        "{\"status\":\"PROPOSED\",\"override\":{\"reason\":\"Behind the WAF\"}}"),
                record(42, Action.APPROVED, "First line of why\nSecond line of why", "not json at all"));
        Path csv = csvFile(records);

        String text = pdfText(evidence(2), records, csv, Instant.parse("2026-11-02T01:30:00Z"),
                ZoneId.of("Asia/Singapore"));

        assertThat(text).startsWith("Decision evidence");
        assertThat(text).contains("Payments API (" + SERVICE + ")", "Records: 2",
                "Generated: 2026-11-02 09:30:00 by Casey Compliance", "Attached CSV: payments.csv",
                "CSV SHA-256: " + EvidenceCsv.sha256(Files.readAllBytes(csv)),
                "2026-10-01 08:00:00 to 2026-11-01 08:00:00 (Asia/Singapore; the end not included)");
        assertThat(text).contains("#41", "Overridden - Risk ticket", "By Alice Analyst - impact then 72.5 (HIGH)",
                "Why: Behind the WAF in ??;", "#42", "Approved - Risk ticket");
        assertThat(text).contains("The responses as they stood", "\"reason\" : \"Behind the WAF\"", "not json at all");
        // Line breaks are kept: the snapshot pretty-printed a member a line, a reason line by line.
        assertThat(text.lines().map(String::strip)).contains("\"status\" : \"PROPOSED\",", "\"override\" : {",
                "Why: First line of why", "Second line of why");
        assertThat(text).contains("page 1 of");
        // What the CSV keeps is said as it is: every field, but a formula made inert.
        assertThat(text.replaceAll("\\s+", " ")).contains("It keeps every field as recorded, except that a field "
                + "beginning with =, +, -, @, a tab or a carriage return starts with ' there")
                .doesNotContain("keeps every field exactly");
    }

    @Test
    void thePdfCarriesItsCsvAttached() throws IOException {
        List<DecisionRecord> records = List.of(record(7, Action.DRAFTED, null, "{\"status\":\"PROPOSED\"}"));
        Path csv = csvFile(records);

        byte[] pdf = pdf(evidence(1), records, csv, FROM, ZoneId.of("UTC"));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            Map<String, PDComplexFileSpecification> attached =
                    document.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
            assertThat(attached).containsOnlyKeys("payments.csv");
            byte[] carried = attached.get("payments.csv").getEmbeddedFile().toByteArray();
            assertThat(carried).isEqualTo(Files.readAllBytes(csv));
            assertThat(attached.get("payments.csv").getEmbeddedFile().getSubtype()).isEqualTo("text/csv");
            // The viewer opens on its attachments, so the CSV is found.
            assertThat(document.getDocumentCatalog().getPageMode()).isEqualTo(PageMode.USE_ATTACHMENTS);
        }
    }

    @Test
    void aMemoCoveringSeveralServicesSaysSoWithoutNamingOne() throws IOException {
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
        UUID memo = UUID.fromString("00000000-0000-0000-0000-0000000000f6");
        // As the trail records a memo: no single service, every one it covers.
        DecisionRecord approved = new DecisionRecord(memo, 7, FROM.plusSeconds(60), Subject.REGULATOR_MEMO, memo, memo,
                null, null, List.of(SERVICE, other), memo, "CVE-2097-0001 in payments-lib", Action.APPROVED, ACTOR,
                null, 88.0, ImpactTier.CRITICAL, "{\"status\":\"APPROVED\"}");

        String text = pdfText(evidence(1), List.of(approved), csvFile(List.of(approved)));

        assertThat(text).contains("Approved - Regulator memo", "Covers 2 services, this one among them")
                .doesNotContain("null");
    }

    @Test
    void aPeriodWithNoDecisionsSaysSo() throws IOException {
        String text = pdfText(evidence(0), List.of(), csvFile(List.of()));

        assertThat(text).contains("Records: 0", "No decision was recorded for this service in this period.")
                .doesNotContain("The responses as they stood");
        assertThat(new String(csv(List.of()), StandardCharsets.UTF_8).split("\r\n")).hasSize(1);
    }

    @Test
    void theFileIsNamedForTheServiceAndTheDaysCovered() {
        assertThat(EvidenceExport.fileName(evidence(0), ZoneId.of("UTC"), Format.PDF))
                .isEqualTo("aegis-evidence-payments-api-2026-10-01-to-2026-10-31.pdf");
        Evidence removed = new Evidence(SERVICE, null, FROM, TO, 0);
        assertThat(EvidenceExport.fileName(removed, ZoneId.of("Asia/Singapore"), Format.CSV))
                .isEqualTo("aegis-evidence-service-2026-10-01-to-2026-11-01.csv");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static byte[] csv(List<DecisionRecord> records) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EvidenceCsv.write(Records.of(records), PEOPLE, out);
        return out.toByteArray();
    }

    private Path csvFile(List<DecisionRecord> records) throws IOException {
        return Files.write(temp.resolve("payments-" + UUID.randomUUID() + ".csv"), csv(records));
    }

    private byte[] pdf(Evidence evidence, List<DecisionRecord> records, Path csv, Instant generatedAt, ZoneId zone)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EvidencePdf.write(evidence, Records.of(records), PEOPLE, new EvidencePdf.Cover("Casey Compliance", generatedAt,
                zone, csv, "payments.csv", EvidenceCsv.sha256(Files.readAllBytes(csv))), JsonMapper.builder().build(),
                out);
        return out.toByteArray();
    }

    private String pdfText(Evidence evidence, List<DecisionRecord> records, Path csv) throws IOException {
        return pdfText(evidence, records, csv, FROM, ZoneId.of("UTC"));
    }

    private String pdfText(Evidence evidence, List<DecisionRecord> records, Path csv, Instant generatedAt,
                           ZoneId zone) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf(evidence, records, csv, generatedAt, zone))) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static Evidence evidence(int records) {
        return new Evidence(SERVICE, "Payments API", FROM, TO, records);
    }

    private static DecisionRecord record(long sequence, Action action, String reason, String state) {
        return record(sequence, action, reason, state, ACTOR);
    }

    private static DecisionRecord record(long sequence, Action action, String reason, String state, UUID actor) {
        UUID response = UUID.nameUUIDFromBytes(("response" + sequence).getBytes(StandardCharsets.UTF_8));
        return new DecisionRecord(UUID.nameUUIDFromBytes(("record" + sequence).getBytes(StandardCharsets.UTF_8)),
                sequence, FROM.plusSeconds(sequence * 3600), Subject.RISK_TICKET, response, response, SERVICE,
                "Payments API", List.of(SERVICE), UUID.nameUUIDFromBytes("change".getBytes(StandardCharsets.UTF_8)),
                "CVE-2097-0001 in payments-lib", action, actor, reason, 72.5, ImpactTier.HIGH, state);
    }
}
