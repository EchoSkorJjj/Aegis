package com.aegis.core.actions.memo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.incidents.VendorIncident;
import com.aegis.core.incidents.VendorIncidentStore;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.news.NewsItem;
import com.aegis.core.news.NewsItemStore;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.FakeAiDrafter;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regulator memos end to end (E8 story 6): a DORA notification drafted only for a change that materially
 * affects a service, final only once a second analyst approves it, and never sent. Every test builds its own
 * uniquely named services, components and changes.
 */
class RegulatorMemoIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String PATH = "/api/actions/regulator-memos";

    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private VendorIncidentStore incidents;
    @Autowired private NewsItemStore newsItems;
    @Autowired private FakeAiDrafter drafter;
    @Autowired private CurrentOrg currentOrg;
    @Autowired private RegulatorMemoService memos;

    private final UUID analyst = UUID.randomUUID();
    private final UUID secondAnalyst = UUID.randomUUID();

    @AfterEach
    void aiBack() {
        drafter.unavailable(false);
    }

    @Test
    void theMemoKeepsItsEvidenceAndCitationsAfterTheEstateAndFeedChange() throws Exception {
        Vulnerability v = vulnerability();
        String created = mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(v.advisory().id())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String originalBody = JsonPath.read(created, "$.body");
        String oldName = v.service().getName();

        inventory.updateBusinessService(v.service().getId(), oldName + " renamed", BusinessCriticality.LOW,
                ServiceExposure.ISOLATED);
        Advisory old = v.advisory();
        advisories.upsert(new Advisory(old.id(), old.sourceId(), old.externalId(), old.canonicalId(),
                "Revised advisory text.", old.publishedAt(), Instant.now(), old.cvss(), old.affected()));

        mvc.perform(get(PATH + "/{id}", id).with(as("COMPLIANCE_ADMIN", secondAnalyst)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body").value(originalBody));
        assertThat(originalBody).contains(oldName, "Source excerpt: A flaw in memo-lib.", "> C1 quoted context")
                .doesNotContain("Revised advisory text.");
    }

    @Test
    void aVulnerabilityIsDraftedAsACyberThreatAndFinalOnlyWhenASecondAnalystApproves() throws Exception {
        Vulnerability v = vulnerability();

        String created = mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(v.advisory().id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.kind").value("CYBER_THREAT"))
                .andExpect(jsonPath("$.signalType").value("VULNERABILITY"))
                .andExpect(jsonPath("$.reference").value(startsWith("AEGIS-")))
                .andExpect(jsonPath("$.services[0].name").value(v.service().getName()))
                .andExpect(jsonPath("$.services[0].material").value(true))
                .andExpect(jsonPath("$.summary").value(FakeAiDrafter.MEMO.summary()))
                .andExpect(jsonPath("$.nextSteps").value(FakeAiDrafter.MEMO.nextSteps()))
                .andExpect(jsonPath("$.aiDrafted").value(true))
                .andExpect(jsonPath("$.citations[0]").value("C1 quoted context"))
                .andExpect(jsonPath("$.createdBy").value(analyst.toString()))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String body = JsonPath.read(created, "$.body");
        assertThat(body).startsWith("# Voluntary notification of a significant cyber threat")
                .contains("- Notification reference: " + JsonPath.read(created, "$.reference"))
                .contains("- Vulnerability: " + v.cve())
                .contains("- Severity published: CVSS 3.1 base score 9.1 (CRITICAL)")
                .contains("- com.acme:" + v.artifact() + " 1.0.0 (maven)")
                .contains("| " + v.service().getName() + " | HIGH | INTERNET_FACING |")
                .contains(FakeAiDrafter.MEMO.measures())
                .contains("Source: osv / " + v.advisory().externalId(), "Source excerpt: A flaw in memo-lib.")
                .contains("Score calculation: severity 0.91 x exposure 1 x criticality 0.8 x confidence 1 x 100 = 72.8")
                .contains("### Sources the AI draft quotes (unverified)", "> C1 quoted context")
                .doesNotContain("{{");
        assertThat(drafter.requests()).anySatisfy(sent -> {
            assertThat(sent.findingId()).isEqualTo(id);
            assertThat(sent.draftType()).isEqualTo("memo");
            assertThat(sent.context()).startsWith("Cyber threat: vulnerability " + v.cve())
                    .contains("A flaw in memo-lib.").contains("- " + v.service().getName() + ": HIGH criticality");
        });

        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", analyst)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("someone else has to approve it")));
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", secondAnalyst)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(secondAnalyst.toString()));
        mvc.perform(post(PATH + "/{id}/dismiss", id).with(as("ANALYST", secondAnalyst))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"too late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A regulator memo that is approved cannot be dismissed"));
    }

    @Test
    void aVendorOutageIsDraftedAsAMajorIncidentTimedFromWhenItStarted() throws Exception {
        String tag = tag();
        BusinessService service = inventory.createBusinessService("Card payments " + tag, BusinessCriticality.CRITICAL,
                ServiceExposure.INTERNET_FACING);
        ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Acme Pay " + tag);
        inventory.addServiceVendor(service.getId(), vendor.getId());
        Instant started = Instant.parse("2026-09-20T06:15:00Z");
        VendorIncident incident = incidents.upsert(new VendorIncident(null, "vendor-status", "acme:memo-" + tag,
                "acme-pay-" + tag, "Acme Pay " + tag, "Card API outage", "investigating", "major", null,
                "Elevated card declines", started, null, null));

        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(incident.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("MAJOR_INCIDENT"))
                .andExpect(jsonPath("$.signalType").value("VENDOR_OUTAGE"))
                .andExpect(jsonPath("$.detectedAt").value(started.toString()))
                .andExpect(jsonPath("$.body").value(startsWith("# Initial notification of a major ICT-related incident")))
                .andExpect(jsonPath("$.body").value(containsString("- Detected: 2026-09-20 06:15 UTC")))
                .andExpect(jsonPath("$.body").value(containsString("no later than 2026-09-21 06:15 UTC")))
                .andExpect(jsonPath("$.body").value(containsString("- Acme Pay " + tag)))
                .andExpect(jsonPath("$.body").value(containsString(
                        "Critical or important functions affected: Yes: " + service.getName() + " (CRITICAL)")));
        assertThat(drafter.requests()).anySatisfy(sent -> assertThat(sent.context())
                .startsWith("ICT-related incident: vendor outage Card API outage at Acme Pay " + tag));
    }

    @Test
    void onlyAnalystsDraftAndDecideWhileComplianceAndOwnersRead() throws Exception {
        Vulnerability v = vulnerability();
        UUID other = UUID.randomUUID();

        for (String role : new String[]{"SERVICE_OWNER", "COMPLIANCE_ADMIN", "ORG_ADMIN", "USER"}) {
            mvc.perform(post(PATH).with(as(role, other)).contentType(MediaType.APPLICATION_JSON)
                    .content(request(v.advisory().id()))).andExpect(status().isForbidden());
        }
        String id = id(mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                .content(request(v.advisory().id()))).andExpect(status().isCreated()));

        for (String role : new String[]{"SERVICE_OWNER", "COMPLIANCE_ADMIN"}) {
            mvc.perform(get(PATH + "/{id}", id).with(as(role, other))).andExpect(status().isOk());
            mvc.perform(post(PATH + "/{id}/approve", id).with(as(role, other))).andExpect(status().isForbidden());
            mvc.perform(post(PATH + "/{id}/dismiss", id).with(as(role, other)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"not mine\"}")).andExpect(status().isForbidden());
        }
        mvc.perform(get(PATH + "/{id}", id).with(as("USER", other))).andExpect(status().isForbidden());
    }

    @Test
    void aChangeThatAffectsNoServiceMateriallyHasNothingToNotify() throws Exception {
        String tag = tag();
        String artifact = "quiet-lib-" + tag;
        BusinessService service = inventory.createBusinessService("Archive " + tag, BusinessCriticality.LOW,
                ServiceExposure.ISOLATED);
        SoftwareComponent component = inventory.createSoftwareComponent("maven", "com.acme:" + artifact, "1.0.0");
        inventory.addServiceComponent(service.getId(), component.getId());
        Advisory advisory = advisories.upsert(new Advisory(null, "osv", "GHSA-" + tag, "CVE-2098-" + tag,
                "A minor flaw.", Instant.now(), Instant.now(), new Advisory.Cvss("3.1", null, new BigDecimal("2.0"), "LOW"),
                List.of(AffectedProduct.ofPackage("Maven", "com.acme:" + artifact, null, "1.0.0", null, null, "1.1.0"))));

        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(advisory.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "No service is materially affected by this change, so there is nothing to notify"));
    }

    @Test
    void aLiveMemoIsNotDraftedTwiceButADismissedOneFreesTheSlot() throws Exception {
        Vulnerability v = vulnerability();
        String body = request(v.advisory().id());
        String id = id(mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()));

        mvc.perform(post(PATH).with(as("ANALYST", secondAnalyst)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already proposed as memo " + id)));
        mvc.perform(post(PATH + "/{id}/dismiss", id).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" Below the major-incident thresholds \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.decidedBy").value(analyst.toString()))
                .andExpect(jsonPath("$.dismissReason").value("Below the major-incident thresholds"));
        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(get(PATH).param("changeSignalId", v.advisory().id().toString()).param("status", "DISMISSED")
                        .with(as("COMPLIANCE_ADMIN", UUID.randomUUID())))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
    }

    @Test
    void anAiOutageNeverBlocksAMemoItFallsBackToAegisOwnFacts() throws Exception {
        Vulnerability v = vulnerability();
        drafter.unavailable(true);

        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(v.advisory().id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aiDrafted").value(false))
                .andExpect(jsonPath("$.citations").isEmpty())
                .andExpect(jsonPath("$.body").value(containsString("Source excerpt: A flaw in memo-lib.")))
                .andExpect(jsonPath("$.body").value(containsString("Score calculation: severity ")))
                .andExpect(jsonPath("$.summary").value("Vulnerability " + v.cve() + ": A flaw in memo-lib."))
                .andExpect(jsonPath("$.impact").value(startsWith(
                        "Aegis assessed 1 service as materially affected: " + v.service().getName() + " (")))
                .andExpect(jsonPath("$.measures").value(MemoTemplate.TO_BE_CONFIRMED));
    }

    @Test
    void onlyAVulnerabilityOrAVendorOutageIsSomethingToNotify() throws Exception {
        String tag = tag();
        NewsItem news = newsItems.upsert(new NewsItem(null, "security-news", "memo-news-" + tag, "feed-" + tag,
                "A security blog", "Attackers are busy " + tag, "https://news.example/" + tag, "Stay alert.",
                Instant.now(), List.of()));

        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(news.id())))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherOrganizationSeesNoneOfIt() throws Exception {
        Vulnerability v = vulnerability();
        UUID id = UUID.fromString(id(mvc.perform(post(PATH).with(as("ANALYST", analyst))
                .contentType(MediaType.APPLICATION_JSON).content(request(v.advisory().id())))
                .andExpect(status().isCreated())));

        Throwable[] seen = new Throwable[1];
        long[] listed = {-1};
        currentOrg.runAs(UUID.randomUUID(), () -> {
            listed[0] = memos.list(v.advisory().id(), null, Pageable.unpaged()).getTotalElements();
            try {
                memos.get(id);
            } catch (Throwable failure) {
                seen[0] = failure;
            }
        });
        assertThat(seen[0]).isInstanceOf(ApiExceptions.NotFound.class);
        assertThat(listed[0]).isZero();
    }

    // ---- fixture -----------------------------------------------------------------------------------

    /** A high-criticality, internet-facing service directly using one Maven component a critical CVE affects. */
    private Vulnerability vulnerability() {
        String tag = tag();
        String artifact = "memo-lib-" + tag;
        BusinessService service = inventory.createBusinessService("Ledger " + tag, BusinessCriticality.HIGH,
                ServiceExposure.INTERNET_FACING);
        SoftwareComponent component = inventory.createSoftwareComponent("maven", "com.acme:" + artifact, "1.0.0");
        inventory.addServiceComponent(service.getId(), component.getId());
        Advisory advisory = advisories.upsert(new Advisory(null, "osv", "GHSA-" + tag, "CVE-2097-" + tag,
                "A flaw in memo-lib.", Instant.now().truncatedTo(ChronoUnit.SECONDS), Instant.now(),
                new Advisory.Cvss("3.1", null, new BigDecimal("9.1"), "CRITICAL"),
                List.of(AffectedProduct.ofPackage("Maven", "com.acme:" + artifact, null, "1.0.0", null, null, "1.1.0"))));
        return new Vulnerability(artifact, service, advisory);
    }

    private record Vulnerability(String artifact, BusinessService service, Advisory advisory) {
        String cve() {
            return advisory.canonicalId();
        }
    }

    private static String tag() {
        return Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
    }

    private static String request(UUID changeSignalId) {
        return "{\"changeSignalId\":\"%s\"}".formatted(changeSignalId);
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static RequestPostProcessor as(String role, UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
