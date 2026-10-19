package com.aegis.core.actions.triage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractIntegrationTest;
import com.aegis.core.support.TestOwners;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fresh orgs and packages make ranking, paging and isolation independent of shared-suite row order. The estate:
 * a CRITICAL internet-facing service reaching the vulnerable library through another package (score 100,
 * TRANSITIVE), and a HIGH isolated service using it directly (40, NO_REPOSITORY) that also depends on a vendor in
 * an active outage (60).
 */
class TriageApiIntegrationTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/findings";

    @Autowired private CurrentOrg currentOrg;
    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private VendorIncidentStore incidents;
    @Autowired private TestOwners owners;

    private UUID org;
    private String tag;
    private String library;
    private BusinessService publicService;
    private BusinessService internalService;
    private Owner owner;
    private Advisory critical;
    private VendorIncident outage;

    @BeforeEach
    void estate() {
        org = UUID.randomUUID();
        tag = UUID.randomUUID().toString();
        library = "triage-lib-" + tag;
        currentOrg.runAs(org, () -> {
            publicService = inventory.createBusinessService("Public " + tag,
                    BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
            internalService = inventory.createBusinessService("Internal " + tag,
                    BusinessCriticality.HIGH, ServiceExposure.ISOLATED);
            owner = owners.assign(internalService.getId(), "Owner " + tag);
            SoftwareComponent direct = inventory.createSoftwareComponent("npm", "direct-" + tag, "1.0.0");
            SoftwareComponent transitive = inventory.createSoftwareComponent("npm", library, "1.0.0");
            inventory.addComponentDependency(direct.getId(), transitive.getId());
            inventory.addServiceComponent(publicService.getId(), direct.getId());
            inventory.addServiceComponent(internalService.getId(), transitive.getId());
            ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Cloud " + tag);
            inventory.addServiceVendor(internalService.getId(), vendor.getId());
        });
        critical = advisory(tag + "1", "10.0");
        advisory(tag + "2", "6.0");
        advisory(tag + "3", "0.1"); // Below materiality on its own; fixed by the same upgrade.
        outage = incident("outage", "investigating", Instant.now(), null);
    }

    @Test
    void ranksVulnerableDependenciesAndActiveOutagesTogetherWithWhatFixesThem() throws Exception {
        mvc.perform(get(PATH).with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.totalElements").value(3))
                .andExpect(jsonPath("$.items.content[*].score").value(contains(100.0, 60.0, 40.0)))
                .andExpect(jsonPath("$.items.content[0].kind").value("VULNERABILITY"))
                .andExpect(jsonPath("$.items.content[0].changeSignalId").value(critical.id().toString()))
                .andExpect(jsonPath("$.items.content[0].service.id").value(publicService.getId().toString()))
                .andExpect(jsonPath("$.items.content[0].tier").value("CRITICAL"))
                .andExpect(jsonPath("$.items.content[0].upgrade.component.name").value(library))
                .andExpect(jsonPath("$.items.content[0].upgrade.toVersion").value("2.0.0"))
                .andExpect(jsonPath("$.items.content[0].upgrade.readiness").value("TRANSITIVE"))
                .andExpect(jsonPath("$.items.content[0].upgrade.vulnerabilities.length()").value(3))
                .andExpect(jsonPath("$.items.content[1].kind").value("VENDOR_OUTAGE"))
                .andExpect(jsonPath("$.items.content[1].outage.id").value(outage.id().toString()))
                .andExpect(jsonPath("$.items.content[1].owners[0].id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.items.content[2].upgrade.readiness").value("NO_REPOSITORY"))
                .andExpect(jsonPath("$.ecosystems").value(contains("npm")));
        // Paging follows the ranking.
        mvc.perform(get(PATH).param("size", "1").param("page", "2").with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content[0].score").value(40.0));
    }

    @Test
    void filtersByServiceOwnerEcosystemAndImpactTier() throws Exception {
        mvc.perform(get(PATH).param("ownerId", owner.getId().toString()).with(as("COMPLIANCE_ADMIN", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content[*].score").value(contains(60.0, 40.0)));
        mvc.perform(get(PATH).param("serviceId", publicService.getId().toString())
                        .param("ownerId", owner.getId().toString()).with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.totalElements").value(0));
        // An ecosystem names packages, so it leaves the outage out.
        mvc.perform(get(PATH).param("ecosystem", "npm").with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content[*].kind").value(everyItem(is("VULNERABILITY"))))
                .andExpect(jsonPath("$.items.totalElements").value(2));
        mvc.perform(get(PATH).param("tier", "MEDIUM").with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content[*].score").value(contains(60.0, 40.0)));
    }

    @Test
    void resolvedAndQuietOutagesLeaveTheQueue() throws Exception {
        incident(outage.externalId(), "resolved", outage.startedAt(), null); // The vendor declared it over.
        Instant month = Instant.now().minus(Duration.ofDays(30));
        incident("quiet", "investigating", month, month); // A feed that never says "resolved".

        mvc.perform(get(PATH).with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content[*].kind").value(everyItem(is("VULNERABILITY"))))
                .andExpect(jsonPath("$.items.totalElements").value(2));
    }

    @Test
    void eachItemCarriesItsResponseAndWhetherTheCallerMayDraftOne() throws Exception {
        String created = mvc.perform(post("/api/actions/risk-tickets").with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}"
                                .formatted(critical.id(), internalService.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String ticketId = JsonPath.read(created, "$.id");

        mvc.perform(get(PATH).with(as("SERVICE_OWNER", owner.getUserId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content[2].response.ticketId").value(ticketId))
                .andExpect(jsonPath("$.items.content[2].response.status").value("PROPOSED"))
                .andExpect(jsonPath("$.items.content[2].response.stage").value("IN_REVIEW"))
                .andExpect(jsonPath("$.items.content[2].stage").value("IN_REVIEW"))
                .andExpect(jsonPath("$.items.content[1].response").doesNotExist())
                .andExpect(jsonPath("$.items.content[1].stage").value("NEW"))
                // A service owner drafts for the services assigned to them only.
                .andExpect(jsonPath("$.items.content[*].canDraft").value(contains(false, true, true)));
        mvc.perform(get(PATH).with(as("COMPLIANCE_ADMIN", owner.getUserId())))
                .andExpect(jsonPath("$.items.content[*].canDraft").value(everyItem(is(false))));
        mvc.perform(get(PATH).with(as("ANALYST", UUID.randomUUID())))
                .andExpect(jsonPath("$.items.content[*].canDraft").value(everyItem(is(true))));
    }

    @Test
    void aDismissedResponseClosesItsItemUntilAnotherIsDrafted() throws Exception {
        String request = "{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}"
                .formatted(critical.id(), internalService.getId());
        String ticketId = JsonPath.read(mvc.perform(post("/api/actions/risk-tickets")
                        .with(as("ANALYST", UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/actions/risk-tickets/{id}/dismiss", ticketId).with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Accepted until the migration\"}"))
                .andExpect(status().isOk());

        // Closed, not new: someone decided no response was needed. Drafting another stays open to them.
        mvc.perform(get(PATH).with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content[2].stage").value("CLOSED"))
                .andExpect(jsonPath("$.items.content[2].response").doesNotExist())
                .andExpect(jsonPath("$.items.content[2].canDraft").value(true))
                .andExpect(jsonPath("$.items.content[1].stage").value("NEW"));
        mvc.perform(get(PATH + "/my-services").with(as("SERVICE_OWNER", owner.getUserId())))
                .andExpect(jsonPath("$[0].toRespond").value(2))
                .andExpect(jsonPath("$[0].noResponseYet").value(1));

        mvc.perform(post("/api/actions/risk-tickets").with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated());
        mvc.perform(get(PATH).with(as("ANALYST", UUID.randomUUID())))
                .andExpect(jsonPath("$.items.content[2].stage").value("IN_REVIEW"));
    }

    @Test
    void mineUsesTheAuthenticatedUserAndFacetsDoNotCrossOrganizations() throws Exception {
        mvc.perform(get(PATH).param("mine", "true").with(as("SERVICE_OWNER", owner.getUserId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[*].id").value(contains(internalService.getId().toString())))
                .andExpect(jsonPath("$.items.content[*].score").value(contains(60.0, 40.0)));
        mvc.perform(get(PATH).param("mine", "true").with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services.length()").value(0))
                .andExpect(jsonPath("$.items.totalElements").value(0));
        mvc.perform(get(PATH).param("serviceId", publicService.getId().toString())
                        .with(as("ANALYST", UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.totalElements").value(0))
                .andExpect(jsonPath("$.services.length()").value(0))
                .andExpect(jsonPath("$.owners.length()").value(0));
    }

    @Test
    void myServicesSaysWhatEachServiceTheCallerOwnsNeeds() throws Exception {
        // The internal service has the outage (60) and the vulnerable library (40) to respond to, nothing drafted.
        mvc.perform(get(PATH + "/my-services").with(as("SERVICE_OWNER", owner.getUserId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].service.id").value(internalService.getId().toString()))
                .andExpect(jsonPath("$[0].toRespond").value(2))
                .andExpect(jsonPath("$[0].noResponseYet").value(2))
                .andExpect(jsonPath("$[0].topScore").value(60.0))
                .andExpect(jsonPath("$[0].topTier").value("MEDIUM"))
                .andExpect(jsonPath("$[0].awaitingDecision").value(0))
                .andExpect(jsonPath("$[0].repositoryUrl").doesNotExist())
                .andExpect(jsonPath("$[0].sbomImportedAt").doesNotExist());

        mvc.perform(post("/api/actions/risk-tickets").with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}"
                                .formatted(critical.id(), internalService.getId())))
                .andExpect(status().isCreated());
        // The drafted response answers one item, and waits in Review & approve until someone decides it.
        mvc.perform(get(PATH + "/my-services").with(as("SERVICE_OWNER", owner.getUserId())))
                .andExpect(jsonPath("$[0].toRespond").value(2))
                .andExpect(jsonPath("$[0].noResponseYet").value(1))
                .andExpect(jsonPath("$[0].awaitingDecision").value(1));

        // Someone who owns no service gets none, and nobody sees another organization's.
        mvc.perform(get(PATH + "/my-services").with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(PATH + "/my-services").with(as("SERVICE_OWNER", owner.getUserId(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void reassigningAServiceRoutesItsItemsAndResponsesToTheNewOwner() throws Exception {
        String drafted = mvc.perform(post("/api/actions/risk-tickets").with(as("SERVICE_OWNER", owner.getUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}"
                                .formatted(critical.id(), internalService.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String ticketId = JsonPath.read(drafted, "$.id");

        // Governance hands the internal service over: the successor is assigned, the previous owner removed.
        Owner[] assigned = new Owner[1];
        currentOrg.runAs(org, () -> {
            assigned[0] = owners.assign(internalService.getId(), "Successor " + tag);
            inventory.removeServiceOwner(internalService.getId(), owner.getId());
        });
        Owner successor = assigned[0];

        // The service's items now name, and are routed to, the successor alone.
        mvc.perform(get(PATH).with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content[1].owners[*].id").value(contains(successor.getId().toString())))
                .andExpect(jsonPath("$.items.content[2].owners[*].id").value(contains(successor.getId().toString())))
                .andExpect(jsonPath("$.owners[*].id").value(contains(successor.getId().toString())));
        mvc.perform(get(PATH).param("ownerId", successor.getId().toString()).with(as("ANALYST", UUID.randomUUID())))
                .andExpect(jsonPath("$.items.content[*].score").value(contains(60.0, 40.0)));
        mvc.perform(get(PATH).param("ownerId", owner.getId().toString()).with(as("ANALYST", UUID.randomUUID())))
                .andExpect(jsonPath("$.items.totalElements").value(0));

        mvc.perform(get(PATH).param("mine", "true").with(as("SERVICE_OWNER", successor.getUserId())))
                .andExpect(jsonPath("$.services[*].id").value(contains(internalService.getId().toString())))
                .andExpect(jsonPath("$.items.content[*].score").value(contains(60.0, 40.0)))
                .andExpect(jsonPath("$.items.content[*].canDraft").value(contains(true, true)));
        mvc.perform(get(PATH + "/my-services").with(as("SERVICE_OWNER", successor.getUserId())))
                .andExpect(jsonPath("$[*].service.id").value(contains(internalService.getId().toString())))
                .andExpect(jsonPath("$[0].awaitingDecision").value(1));

        // The previous owner keeps nothing: no queue, no services, no right to draft or decide.
        mvc.perform(get(PATH).param("mine", "true").with(as("SERVICE_OWNER", owner.getUserId())))
                .andExpect(jsonPath("$.services.length()").value(0))
                .andExpect(jsonPath("$.items.totalElements").value(0));
        mvc.perform(get(PATH + "/my-services").with(as("SERVICE_OWNER", owner.getUserId())))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(PATH).with(as("SERVICE_OWNER", owner.getUserId())))
                .andExpect(jsonPath("$.items.content[*].canDraft").value(everyItem(is(false))));
        mvc.perform(post("/api/actions/risk-tickets").with(as("SERVICE_OWNER", owner.getUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}"
                                .formatted(outage.id(), internalService.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/actions/risk-tickets/{id}/dismiss", ticketId)
                        .with(as("SERVICE_OWNER", owner.getUserId())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"No longer mine\"}"))
                .andExpect(status().isForbidden());

        // The response drafted before the handover is the successor's to decide.
        mvc.perform(post("/api/actions/risk-tickets/{id}/dismiss", ticketId)
                        .with(as("SERVICE_OWNER", successor.getUserId())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Accepted until the migration\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
    }

    @Test
    void enforcesDomainReadRolesAndRejectsInvalidFilters() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/my-services")).andExpect(status().isUnauthorized());
        for (String role : List.of("USER", "ORG_ADMIN", "PLATFORM_ADMIN")) {
            mvc.perform(get(PATH).with(as(role, UUID.randomUUID()))).andExpect(status().isForbidden());
            mvc.perform(get(PATH + "/my-services").with(as(role, UUID.randomUUID()))).andExpect(status().isForbidden());
        }
        mvc.perform(get(PATH).param("ownerId", "invalid").with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("tier", "SEVERE").with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    private Advisory advisory(String suffix, String score) {
        Instant old = Instant.parse("2021-01-01T00:00:00Z");
        return advisories.upsert(new Advisory(null, "nvd", "CVE-2099-" + suffix, "Triage " + suffix, old, old,
                new Advisory.Cvss("3.1", null, new BigDecimal(score), "CRITICAL"),
                List.of(AffectedProduct.ofPackage("npm", library, null, "0", null, null, "2.0.0"))));
    }

    private VendorIncident incident(String externalId, String status, Instant startedAt, Instant lastUpdateAt) {
        return incidents.upsert(new VendorIncident(null, "vendor-status",
                externalId.startsWith("outage-") ? externalId : externalId + "-" + tag, "cloud-" + tag,
                "Cloud " + tag, "API outage", status, "major", null, "Elevated errors", startedAt, lastUpdateAt, null));
    }

    private RequestPostProcessor as(String role, UUID user) {
        return as(role, user, org);
    }

    private static RequestPostProcessor as(String role, UUID user, UUID org) {
        return jwt().jwt(builder -> builder.subject(user.toString()).claim("org", org.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
