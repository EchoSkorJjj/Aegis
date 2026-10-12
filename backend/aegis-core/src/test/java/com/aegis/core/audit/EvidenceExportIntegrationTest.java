package com.aegis.core.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.audit.DecisionRecord.Action;
import com.aegis.core.audit.DecisionRecord.Subject;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.demo.DemoEstateSeeder;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.platform.identity.DirectoryUser;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.FakeIdentityDirectory;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Evidence export (E10 story 3; its correctness, CS203-144): an export for a service and period holds exactly the
 * records the trail shows for that service and period, in its order, field for field and snapshot for snapshot, and
 * the PDF shows those same records and the CSV's digest. Records are appended directly (how decisions write them is
 * {@link DecisionTrailIntegrationTest}'s), each test under services of its own, so the shared database's other
 * records never reach its exports.
 */
class EvidenceExportIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String EVIDENCE = "/api/audit/evidence";
    private static final String TRAIL = "/api/audit/decisions";
    private static final String FAR_FUTURE = "2999-01-01T00:00:00Z";

    @Autowired private DecisionTrail trail;
    @Autowired private InventoryService inventory;
    @Autowired private FakeIdentityDirectory directory;
    @Autowired private ObjectMapper mapper;

    private DirectoryUser alice;
    private DirectoryUser casey;
    private final UUID formerMember = UUID.randomUUID();

    @BeforeEach
    void members() {
        alice = directory.member("Alice Analyst", "ANALYST");
        casey = directory.member("Casey Compliance", "COMPLIANCE_ADMIN");
    }

    @Test
    void theCsvHoldsExactlyWhatTheTrailShowsForTheServiceAndPeriod() throws Exception {
        Fixture f = fixture();

        MockHttpServletResponse response = export(get(EVIDENCE).param("serviceId", f.service().toString())
                .param("from", f.periodStart()).param("to", FAR_FUTURE).param("format", "csv"));
        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition"))
                .startsWith("attachment; filename=\"aegis-evidence-evidence-");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        byte[] csv = response.getContentAsByteArray();
        assertThat(response.getHeader(EvidenceController.CSV_SHA256)).isEqualTo(EvidenceCsv.sha256(csv));

        List<Map<String, String>> rows = parse(csv);
        String listing = trailListing(f.service(), f.periodStart());
        List<String> ids = JsonPath.read(listing, "$.content[*].id");
        // The trail's records for the service in the period, in its order; not the one before it, nor another
        // service's, but the memo covering this service among others.
        assertThat(rows).extracting(row -> row.get("record_id")).containsExactlyElementsOf(ids);
        assertThat(rows).extracting(row -> row.get("action"))
                .containsExactly("APPROVED", "OVERRIDDEN", "DISMISSED", "APPROVED");
        assertThat(response.getHeader(EvidenceController.RECORDS)).isEqualTo("4");

        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            String at = "$.content[%d].".formatted(i);
            assertThat(row.get("sequence")).isEqualTo(JsonPath.read(listing, at + "sequence").toString());
            assertThat(Instant.parse(row.get("occurred_at")))
                    .isEqualTo(Instant.parse(JsonPath.read(listing, at + "occurredAt")));
            assertThat(row.get("subject")).isEqualTo(JsonPath.read(listing, at + "subject"));
            assertThat(row.get("response_id")).isEqualTo(JsonPath.read(listing, at + "responseId"));
            assertThat(row.get("actor_id")).isEqualTo(JsonPath.read(listing, at + "actorId"));
            assertThat(row.get("change_title")).isEqualTo(inert(JsonPath.read(listing, at + "changeTitle")));
            assertThat(row.get("reason")).isEqualTo(inert(orEmpty(JsonPath.read(listing, at + "reason"))));
            assertThat(row.get("tier")).isEqualTo(orEmpty(JsonPath.read(listing, at + "tier")));
            assertThat(row.get("score")).isEqualTo(orEmpty(JsonPath.read(listing, at + "score")));
            // The snapshot, as the record read on its own has it.
            String detail = mvc.perform(get(TRAIL + "/{id}", row.get("record_id")).with(as("COMPLIANCE_ADMIN", casey)))
                    .andReturn().getResponse().getContentAsString();
            JsonNode recorded = mapper.readTree(detail).get("state");
            assertThat(mapper.readTree(row.get("state"))).isEqualTo(recorded);
        }

        // People by name where the directory knows them, by id where they've left; formulas made inert.
        assertThat(rows.get(0).get("actor_name")).isEqualTo("Alice Analyst");
        assertThat(rows.get(1).get("actor_name")).isEqualTo("Unknown member " + formerMember);
        assertThat(rows.get(1).get("reason")).isEqualTo("'=HYPERLINK(\"https://evil.example\",\"WAF\")");
        assertThat(rows.get(2).get("service_ids").split(" ")).hasSize(2);
        assertThat(rows.get(0).get("service_name")).isEqualTo(f.serviceName());
    }

    @Test
    void thePdfShowsTheSameRecordsAndTheDigestOfTheCsv() throws Exception {
        Fixture f = fixture();
        MockHttpServletResponse csv = export(get(EVIDENCE).param("serviceId", f.service().toString())
                .param("from", f.periodStart()).param("to", FAR_FUTURE));
        MockHttpServletResponse pdf = export(get(EVIDENCE).param("serviceId", f.service().toString())
                .param("from", f.periodStart()).param("to", FAR_FUTURE).param("format", "PDF")
                .param("zone", "Asia/Singapore"));

        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(pdf.getHeader("Content-Disposition")).endsWith(".pdf\"");
        String digest = csv.getHeader(EvidenceController.CSV_SHA256);
        assertThat(pdf.getHeader(EvidenceController.CSV_SHA256)).isEqualTo(digest);

        String text = text(pdf.getContentAsByteArray());
        List<Map<String, String>> rows = parse(csv.getContentAsByteArray());
        assertThat(text).contains("CSV SHA-256: " + digest, "Records: 4", "Generated: ", "by Casey Compliance",
                "(Asia/Singapore; the end not included)", f.serviceName());
        for (Map<String, String> row : rows) {
            assertThat(text).contains("#" + row.get("sequence"),
                    EvidencePdf.time(Instant.parse(row.get("occurred_at")), ZoneId.of("Asia/Singapore")));
            assertThat(text).contains("record " + row.get("record_id"));
        }
        assertThat(text).contains("By Alice Analyst - impact then 72.5 (HIGH)", "Why: Accepted for this quarter",
                "Covers 2 services", "The responses as they stood", "\"note\" : \"step 2\"");
    }

    @Test
    void aRemovedServicesEvidenceIsStillExportedUnderTheNameItsRecordsGaveIt() throws Exception {
        UUID removed = UUID.randomUUID();
        record(removed, "Retired billing", Subject.RISK_TICKET, Action.DISMISSED, alice.id(), "Service retired");

        MockHttpServletResponse response = export(get(EVIDENCE).param("serviceId", removed.toString())
                .param("from", "2000-01-01T00:00:00Z").param("to", FAR_FUTURE));
        assertThat(response.getHeader("Content-Disposition")).contains("aegis-evidence-retired-billing-2000-01-01-to-");
        assertThat(parse(response.getContentAsByteArray())).singleElement()
                .satisfies(row -> assertThat(row.get("reason")).isEqualTo("Service retired"));
    }

    @Test
    void aPeriodWithoutDecisionsExportsNoRecords() throws Exception {
        UUID service = UUID.randomUUID();
        MockHttpServletResponse response = export(get(EVIDENCE).param("serviceId", service.toString())
                .param("from", "2001-01-01T00:00:00Z").param("to", "2001-02-01T00:00:00Z").param("format", "pdf"));
        assertThat(response.getHeader(EvidenceController.RECORDS)).isEqualTo("0");
        assertThat(text(response.getContentAsByteArray()))
                .contains("No decision was recorded for this service in this period.");
    }

    @Test
    void anExportThatCannotBeMadeIsRefused() throws Exception {
        String service = UUID.randomUUID().toString();
        String from = "2026-10-01T00:00:00Z";
        String to = "2026-11-01T00:00:00Z";
        refused(get(EVIDENCE).param("from", from).param("to", to), "needs a service");
        refused(get(EVIDENCE).param("serviceId", service).param("to", to), "needs a service");
        refused(get(EVIDENCE).param("serviceId", service).param("from", from), "needs a service");
        refused(get(EVIDENCE).param("serviceId", service).param("from", to).param("to", from), "before its end");
        refused(get(EVIDENCE).param("serviceId", service).param("from", from).param("to", to).param("format", "docx"),
                "csv or pdf");
        refused(get(EVIDENCE).param("serviceId", service).param("from", from).param("to", to).param("zone", "Mars/Base"),
                "Unknown time zone");
    }

    @Test
    void onlyGovernanceExportsEvidence() throws Exception {
        String service = UUID.randomUUID().toString();
        MockHttpServletRequestBuilder request = get(EVIDENCE).param("serviceId", service)
                .param("from", "2026-10-01T00:00:00Z").param("to", "2026-11-01T00:00:00Z");
        mvc.perform(request.with(as("SERVICE_OWNER", alice))).andExpect(status().isForbidden());
        mvc.perform(get(EVIDENCE).param("serviceId", service)).andExpect(status().isUnauthorized());
        mvc.perform(request.with(as("ANALYST", alice))).andExpect(status().isOk())
                .andExpect(header().string(EvidenceController.RECORDS, "0"));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /**
     * A service with a step before the period, then in it: Alice's approval, an override by someone who has since
     * left (its reason a would-be formula), a regulator memo covering it and another service, and Alice's approval
     * again; plus a step in another service only.
     */
    private Fixture fixture() throws Exception {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        String name = "Evidence " + tag;
        BusinessService service = inventory.createBusinessService(name, BusinessCriticality.HIGH,
                ServiceExposure.INTERNET_FACING);
        UUID id = service.getId();
        UUID other = UUID.randomUUID();
        record(id, name, Subject.RISK_TICKET, Action.DRAFTED, alice.id(), null);
        Thread.sleep(20);
        DecisionRecord first = record(id, name, Subject.RISK_TICKET, Action.APPROVED, alice.id(), null);
        record(id, name, Subject.RISK_TICKET, Action.OVERRIDDEN, formerMember,
                "=HYPERLINK(\"https://evil.example\",\"WAF\")");
        record(other, "Elsewhere", Subject.RISK_TICKET, Action.DISMISSED, alice.id(), "Not this service");
        trail.recorded(UUID::randomUUID, memo -> new DecisionRecord.Entry(Subject.REGULATOR_MEMO, memo, memo, null,
                name, List.of(id, other), UUID.randomUUID(), "CVE-2097-0003 in evidence-lib", Action.DISMISSED,
                alice.id(), "Accepted for this quarter", 88.0, ImpactTier.CRITICAL, Map.of("note", "memo")));
        record(id, name, Subject.UPGRADE_PULL_REQUEST, Action.APPROVED, alice.id(), null);
        return new Fixture(id, name, first.occurredAt().toString());
    }

    private record Fixture(UUID service, String serviceName, String periodStart) {
    }

    private int step;

    private DecisionRecord record(UUID service, String name, Subject subject, Action action, UUID actor,
                                  String reason) {
        int n = ++step;
        UUID response = trail.recorded(UUID::randomUUID, saved -> new DecisionRecord.Entry(subject, saved, saved,
                service, name, List.of(service), UUID.randomUUID(), "CVE-2097-0003 in evidence-lib", action, actor,
                reason, 72.5, ImpactTier.HIGH, Map.of("note", "step " + n, "status", action.name())));
        String listing;
        try {
            listing = mvc.perform(get(TRAIL).param("responseId", response.toString())
                    .with(as("COMPLIANCE_ADMIN", casey))).andReturn().getResponse().getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new DecisionRecord(UUID.fromString(JsonPath.read(listing, "$.content[0].id")), 0,
                Instant.parse(JsonPath.read(listing, "$.content[0].occurredAt")), subject, response, response,
                service, name, List.of(service), UUID.randomUUID(), "", action, actor, reason, 72.5, ImpactTier.HIGH,
                null);
    }

    private String trailListing(UUID service, String from) throws Exception {
        return mvc.perform(get(TRAIL).param("serviceId", service.toString()).param("from", from)
                        .param("to", FAR_FUTURE).param("size", "100").with(as("COMPLIANCE_ADMIN", casey)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private MockHttpServletResponse export(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(as("COMPLIANCE_ADMIN", casey)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    private void refused(MockHttpServletRequestBuilder request, String why) throws Exception {
        mvc.perform(request.with(as("COMPLIANCE_ADMIN", casey)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(why)));
    }

    /** A text field as the CSV writes it: one a spreadsheet would run as a formula starts with a quote. */
    private static String inert(String value) {
        return !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }

    private static String orEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** The CSV as rows keyed by column: RFC 4180, every field quoted, the byte-order mark first. */
    static List<Map<String, String>> parse(byte[] bytes) {
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿");
        List<List<String>> lines = new ArrayList<>();
        List<String> line = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 1; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                line.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                line.add(field.toString());
                field.setLength(0);
                lines.add(line);
                line = new ArrayList<>();
                i++;
            } else {
                throw new AssertionError("Unquoted character " + c + " at " + i);
            }
        }
        assertThat(lines.get(0)).containsExactlyElementsOf(EvidenceCsv.COLUMNS);
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> values : lines.subList(1, lines.size())) {
            assertThat(values).hasSameSizeAs(EvidenceCsv.COLUMNS);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < values.size(); c++) {
                row.put(EvidenceCsv.COLUMNS.get(c), values.get(c));
            }
            rows.add(row);
        }
        return rows;
    }

    private static RequestPostProcessor as(String role, DirectoryUser user) {
        return jwt().jwt(token -> token.subject(user.id().toString())
                        .claim("org", DemoEstateSeeder.DEMO_ORG_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
