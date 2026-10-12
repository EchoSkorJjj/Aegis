package com.aegis.core.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.audit.DecisionRecord.Action;
import com.aegis.core.audit.DecisionRecord.Subject;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evidence writers on their own, no database: the CSV quotes every field and makes formulas inert, its bytes (and
 * so its digest) depend only on the evidence, and the PDF prints what its fonts can and survives what they can't.
 * That an export holds exactly the trail's records is {@link EvidenceExportIntegrationTest}'s.
 */
class EvidenceWritersTest {

    private static final UUID SERVICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-01T00:00:00Z");

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
    void theCsvHasAHeaderARowPerRecordAndTheSameBytesForTheSameEvidence() {
        EvidenceExport.Evidence evidence = evidence(List.of(
                record(1, Action.DRAFTED, null, "{\"status\":\"PROPOSED\"}"),
                record(2, Action.DISMISSED, "=cmd|' /C calc'!A0", "{\"status\":\"DISMISSED\"}")));

        byte[] csv = EvidenceCsv.write(evidence);
        String text = new String(csv, StandardCharsets.UTF_8);

        assertThat(text).startsWith("﻿\"record_id\",\"sequence\",\"occurred_at\"");
        assertThat(text.split("\r\n")).hasSize(3);
        assertThat(text).contains("\"Alice Analyst\"", "\"'=cmd|' /C calc'!A0\"", "\"{\"\"status\"\":\"\"DISMISSED\"\"}\"");
        assertThat(EvidenceCsv.write(evidence)).isEqualTo(csv);
        assertThat(EvidenceCsv.sha256(csv)).hasSize(64).isEqualTo(EvidenceCsv.sha256(EvidenceCsv.write(evidence)));
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
        EvidenceExport.Evidence evidence = evidence(List.of(
                record(41, Action.OVERRIDDEN, "Behind the WAF in 東京; " + longWord,
                        "{\"status\":\"PROPOSED\",\"override\":{\"reason\":\"Behind the WAF\"}}"),
                record(42, Action.APPROVED, "First line of why\nSecond line of why", "not json at all")));

        byte[] pdf = EvidencePdf.write(evidence, new EvidencePdf.Cover("Casey Compliance",
                Instant.parse("2026-11-02T01:30:00Z"), ZoneId.of("Asia/Singapore"), "ab".repeat(32)),
                JsonMapper.builder().build());

        String text = text(pdf);
        assertThat(text).startsWith("Decision evidence");
        assertThat(text).contains("Payments API (" + SERVICE + ")", "Records: 2",
                "Generated: 2026-11-02 09:30:00 by Casey Compliance", "CSV SHA-256: " + "ab".repeat(32),
                "2026-10-01 08:00:00 to 2026-11-01 08:00:00 (Asia/Singapore; the end not included)");
        assertThat(text).contains("#41", "Overridden - Risk ticket", "By Alice Analyst - impact then 72.5 (HIGH)",
                "Why: Behind the WAF in ??;", "#42", "Approved - Risk ticket");
        assertThat(text).contains("The responses as they stood", "\"reason\" : \"Behind the WAF\"", "not json at all");
        // Line breaks are kept: the snapshot pretty-printed a member a line, a reason line by line.
        assertThat(text.lines().map(String::strip)).contains("\"status\" : \"PROPOSED\",", "\"override\" : {",
                "Why: First line of why", "Second line of why");
        assertThat(text).contains("page 1 of");
    }

    @Test
    void aMemoCoveringSeveralServicesSaysSoWithoutNamingOne() throws IOException {
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
        UUID memo = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
        // As the trail records a memo: no single service, every one it covers.
        DecisionRecord approved = new DecisionRecord(memo, 7, FROM.plusSeconds(60), Subject.REGULATOR_MEMO, memo, memo,
                null, null, List.of(SERVICE, other), memo, "CVE-2097-0001 in payments-lib", Action.APPROVED, ACTOR,
                null, 88.0, ImpactTier.CRITICAL, "{\"status\":\"APPROVED\"}");

        String text = text(EvidencePdf.write(evidence(List.of(approved)), new EvidencePdf.Cover("Casey", FROM,
                ZoneId.of("UTC"), "0".repeat(64)), JsonMapper.builder().build()));

        assertThat(text).contains("Approved - Regulator memo", "Covers 2 services, this one among them")
                .doesNotContain("null");
    }

    @Test
    void aPeriodWithNoDecisionsSaysSo() throws IOException {
        byte[] pdf = EvidencePdf.write(evidence(List.of()), new EvidencePdf.Cover("Casey", FROM, ZoneId.of("UTC"),
                "0".repeat(64)), JsonMapper.builder().build());

        assertThat(text(pdf)).contains("Records: 0", "No decision was recorded for this service in this period.")
                .doesNotContain("The responses as they stood");
        assertThat(new String(EvidenceCsv.write(evidence(List.of())), StandardCharsets.UTF_8).split("\r\n"))
                .hasSize(1);
    }

    @Test
    void theFileIsNamedForTheServiceAndTheDaysCovered() {
        assertThat(EvidenceController.fileName(evidence(List.of()), ZoneId.of("UTC"), "pdf"))
                .isEqualTo("aegis-evidence-payments-api-2026-10-01-to-2026-10-31.pdf");
        EvidenceExport.Evidence removed = new EvidenceExport.Evidence(SERVICE, null, FROM, TO, List.of(), Map.of());
        assertThat(EvidenceController.fileName(removed, ZoneId.of("Asia/Singapore"), "csv"))
                .isEqualTo("aegis-evidence-service-2026-10-01-to-2026-11-01.csv");
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static EvidenceExport.Evidence evidence(List<DecisionRecord> records) {
        return new EvidenceExport.Evidence(SERVICE, "Payments API", FROM, TO, records,
                Map.of(ACTOR, "Alice Analyst"));
    }

    private static DecisionRecord record(long sequence, Action action, String reason, String state) {
        UUID response = UUID.nameUUIDFromBytes(("response" + sequence).getBytes(StandardCharsets.UTF_8));
        return new DecisionRecord(UUID.nameUUIDFromBytes(("record" + sequence).getBytes(StandardCharsets.UTF_8)),
                sequence, FROM.plusSeconds(sequence * 3600), Subject.RISK_TICKET, response, response, SERVICE,
                "Payments API", List.of(SERVICE), UUID.nameUUIDFromBytes("change".getBytes(StandardCharsets.UTF_8)),
                "CVE-2097-0001 in payments-lib", action, ACTOR, reason, 72.5, ImpactTier.HIGH, state);
    }
}
