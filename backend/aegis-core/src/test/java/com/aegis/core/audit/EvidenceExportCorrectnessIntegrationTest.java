package com.aegis.core.audit;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.demo.DemoEstateSeeder;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.platform.identity.DirectoryUser;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.FakeIdentityDirectory;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Export correctness (CS203-144), from real decisions: a service's responses are drafted, overridden, approved, filed
 * and dismissed through the API at three moments, and the export of each period between them — as CSV and as PDF —
 * holds every decision recorded for the service in that period and nothing outside it, checked against the service's
 * whole trail filtered here by time, not against the export's own query. Back-to-back periods together hold every
 * record exactly once. (Field-by-field agreement with the trail, snapshots included:
 * {@link EvidenceExportIntegrationTest}.)
 */
class EvidenceExportCorrectnessIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String EVIDENCE = "/api/audit/evidence";
    private static final String TRAIL = "/api/audit/decisions";
    private static final String TICKETS = "/api/actions/risk-tickets";
    private static final String MEMOS = "/api/actions/regulator-memos";

    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private FakeIdentityDirectory directory;

    private DirectoryUser drafter;
    private DirectoryUser reviewer;
    private DirectoryUser auditor;

    @BeforeEach
    void members() {
        drafter = directory.member("Dana Drafter", "ANALYST");
        reviewer = directory.member("Riley Reviewer", "ANALYST");
        auditor = directory.member("Casey Compliance", "COMPLIANCE_ADMIN");
    }

    @Test
    void eachPeriodsExportHoldsEveryDecisionInItAndNothingOutsideIt() throws Exception {
        Fixture f = fixture();

        // Before: a risk ticket drafted.
        String ticket = id(mvc.perform(post(TICKETS).with(as("ANALYST", drafter)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}"
                                .formatted(f.advisory().id(), f.service().getId())))
                .andExpect(status().isCreated()));
        Thread.sleep(25);
        // During: overridden with a reason, approved and filed; a regulator memo drafted.
        mvc.perform(post(TICKETS + "/{id}/override", ticket).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"priority\":\"LOW\",\"dueDate\":null,\"reason\":\"Behind the WAF\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(TICKETS + "/{id}/approve", ticket).with(as("ANALYST", reviewer))).andExpect(status().isOk());
        String memo = id(mvc.perform(post(MEMOS).with(as("ANALYST", drafter)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\"}".formatted(f.advisory().id())))
                .andExpect(status().isCreated()));
        Thread.sleep(25);
        // After: the memo dismissed.
        mvc.perform(post(MEMOS + "/{id}/dismiss", memo).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Not a significant threat\"}"))
                .andExpect(status().isOk());

        // The service's whole trail, as the Audit log shows it, unfiltered by time.
        List<Row> trail = wholeTrail(f.service().getId());
        assertThat(trail).extracting(Row::action)
                .containsExactly("DRAFTED", "OVERRIDDEN", "APPROVED", "FILED", "DRAFTED", "DISMISSED");
        Instant start = trail.get(0).at();
        Instant during = trail.get(1).at();
        Instant after = trail.get(5).at();
        Instant end = after.plusSeconds(1);
        assertThat(start).isBefore(during);
        assertThat(trail.get(4).at()).isBefore(after);

        List<List<String>> exported = new ArrayList<>();
        for (Instant[] period : List.of(new Instant[] {start, during}, new Instant[] {during, after},
                new Instant[] {after, end})) {
            List<Row> expected = trail.stream()
                    .filter(row -> !row.at().isBefore(period[0]) && row.at().isBefore(period[1]))
                    .toList();
            List<String> expectedIds = expected.stream().map(Row::id).toList();
            List<String> outside = trail.stream().map(Row::id).filter(id -> !expectedIds.contains(id)).toList();

            // CSV: exactly the period's records, in the trail's order.
            MockHttpServletResponse csv = export(f.service().getId(), period[0], period[1], "csv");
            List<Map<String, String>> rows = EvidenceExportIntegrationTest.parse(csv.getContentAsByteArray());
            assertThat(rows).extracting(row -> row.get("record_id")).containsExactlyElementsOf(expectedIds);
            assertThat(rows).extracting(row -> row.get("action"))
                    .containsExactlyElementsOf(expected.stream().map(Row::action).toList());
            assertThat(csv.getHeader(EvidenceController.RECORDS)).isEqualTo(Integer.toString(expected.size()));

            // PDF: every one of them, by record id, and none of the others.
            MockHttpServletResponse pdf = export(f.service().getId(), period[0], period[1], "pdf");
            String text = text(pdf.getContentAsByteArray());
            assertThat(text).contains("Records: " + expected.size(),
                    "CSV SHA-256: " + csv.getHeader(EvidenceController.CSV_SHA256));
            for (Row row : expected) {
                assertThat(text).contains("record " + row.id(), "#" + row.sequence(),
                        EvidencePdf.label(DecisionRecord.Action.valueOf(row.action())));
                if (row.reason() != null) {
                    assertThat(text).contains("Why: " + row.reason());
                }
            }
            for (String id : outside) {
                assertThat(text).doesNotContain(id);
            }
            exported.add(expectedIds);
        }

        // Back to back, the periods hold the whole trail, each record once.
        assertThat(exported.get(0)).hasSize(1);
        assertThat(exported.get(1)).hasSize(4);
        assertThat(exported.get(2)).hasSize(1);
        assertThat(exported.stream().flatMap(List::stream).toList())
                .containsExactlyElementsOf(trail.stream().map(Row::id).toList());
        // The decisions are the people's who took them, as the trail shows.
        String duringText = text(export(f.service().getId(), during, after, "pdf").getContentAsByteArray());
        assertThat(duringText).contains("By Riley Reviewer", "By Dana Drafter", "Why: Behind the WAF");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private record Row(String id, long sequence, Instant at, String action, String reason) {
    }

    private List<Row> wholeTrail(UUID service) throws Exception {
        String listing = mvc.perform(get(TRAIL).param("serviceId", service.toString()).param("size", "100")
                        .with(as("COMPLIANCE_ADMIN", auditor)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int count = JsonPath.read(listing, "$.content.length()");
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String at = "$.content[%d].".formatted(i);
            rows.add(new Row(JsonPath.read(listing, at + "id"),
                    ((Number) JsonPath.read(listing, at + "sequence")).longValue(),
                    Instant.parse(JsonPath.read(listing, at + "occurredAt")),
                    JsonPath.read(listing, at + "action"),
                    JsonPath.read(listing, at + "reason")));
        }
        return rows;
    }

    private MockHttpServletResponse export(UUID service, Instant from, Instant to, String format) throws Exception {
        return mvc.perform(get(EVIDENCE).param("serviceId", service.toString()).param("from", from.toString())
                        .param("to", to.toString()).param("format", format)
                        .with(as("COMPLIANCE_ADMIN", auditor)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** A high-criticality, internet-facing service directly using one Maven component a critical CVE affects. */
    private Fixture fixture() {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        String artifact = "export-lib-" + tag;
        BusinessService service = inventory.createBusinessService("Export " + tag, BusinessCriticality.HIGH,
                ServiceExposure.INTERNET_FACING);
        SoftwareComponent component = inventory.createSoftwareComponent("maven", "com.acme:" + artifact, "1.0.0");
        inventory.addServiceComponent(service.getId(), component.getId());
        Advisory advisory = advisories.upsert(new Advisory(null, "osv", "GHSA-" + tag, "CVE-2098-" + tag,
                "A flaw in export-lib.", Instant.now(), Instant.now(),
                new Advisory.Cvss("3.1", null, new BigDecimal("9.8"), "CRITICAL"),
                List.of(AffectedProduct.ofPackage("Maven", "com.acme:" + artifact, null, "1.0.0", null, null,
                        "1.1.0"))));
        return new Fixture(service, advisory);
    }

    private record Fixture(BusinessService service, Advisory advisory) {
    }

    private static RequestPostProcessor as(String role, DirectoryUser user) {
        return jwt().jwt(token -> token.subject(user.id().toString())
                        .claim("org", DemoEstateSeeder.DEMO_ORG_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
