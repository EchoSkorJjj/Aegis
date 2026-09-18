package com.aegis.core.matching;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AdvisoryStore;
import com.aegis.core.ingestion.advisory.AffectedProduct;
import com.aegis.core.ingestion.incident.VendorIncident;
import com.aegis.core.ingestion.incident.VendorIncidentStore;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MatchingApiIntegrationTest extends AbstractInventoryIntegrationTest {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private AdvisoryStore advisories;

    @Autowired
    private VendorIncidentStore incidents;

    @Test
    void matchesAnAdvisoryAndReturnsTheTransitiveEvidenceChainToOwners() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService service = inventory.createBusinessService("Checkout " + suffix, BusinessCriticality.HIGH);
        Owner owner = inventory.createOwner("Payments team " + suffix);
        inventory.addServiceOwner(service.getId(), owner.getId());

        String affectedProduct = "match-lib-" + suffix;
        SoftwareComponent vulnerable = inventory.createSoftwareComponent(
                "maven", "com.acme:" + affectedProduct, "2.14.1");
        SoftwareComponent direct = inventory.createSoftwareComponent("maven", "com.acme:checkout-" + suffix, "1.0.0");
        inventory.createSoftwareComponent("maven", "com.acme:" + affectedProduct, "2.17.1");
        inventory.addComponentDependency(direct.getId(), vulnerable.getId());
        inventory.addServiceComponent(service.getId(), direct.getId());
        // The fixed version is registered but deliberately not linked; it must not count as a match.

        String cve = "CVE-2099-" + Math.abs(UUID.randomUUID().getMostSignificantBits());
        Advisory advisory = advisories.upsert(new Advisory(
                null, "nvd", cve, "test advisory", Instant.now(), Instant.now(), null,
                List.of(new AffectedProduct(
                        "cpe:2.3:a:acme:" + affectedProduct + ":*:*:*:*:*:*:*:*",
                        "a", "acme", affectedProduct, null, "2.0.0", null, null, "2.15.0"))));

        mvc.perform(get("/api/matching/change-signals/{id}", advisory.id()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.signalType").value("VULNERABILITY"))
                .andExpect(jsonPath("$.change.externalId").value(cve))
                .andExpect(jsonPath("$.matchedComponentCount").value(1))
                .andExpect(jsonPath("$.matchedVendorCount").value(0))
                .andExpect(jsonPath("$.impactedServices.length()").value(1))
                .andExpect(jsonPath("$.impactedServices[0].service.id").value(service.getId().toString()))
                .andExpect(jsonPath("$.impactedServices[0].owners[0].id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.impactedServices[0].confidence").value("CONFIRMED"))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].kind").value("ADVISORY_COMPONENT"))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].confidence").value("CONFIRMED"))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].affectedProduct.identityMatch")
                        .value("EXACT_PACKAGE"))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].affectedProduct.versionMatch")
                        .value("IN_RANGE"))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].affectedProduct.versionRange")
                        .value("[2.0.0,2.15.0)"))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].componentPath.length()").value(2))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].componentPath[0].id")
                        .value(vulnerable.getId().toString()))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].componentPath[1].id")
                        .value(direct.getId().toString()));
    }

    @Test
    void matchesAVendorOutageToDependentServicesAndOwners() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService service = inventory.createBusinessService("Orders " + suffix, BusinessCriticality.CRITICAL);
        Owner owner = inventory.createOwner("Orders team " + suffix);
        ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Acme Cloud " + suffix);
        inventory.addServiceOwner(service.getId(), owner.getId());
        inventory.addServiceVendor(service.getId(), vendor.getId());

        VendorIncident incident = incidents.upsert(new VendorIncident(
                null,
                "vendor-status",
                "acme:incident-" + suffix,
                "acme-cloud-" + suffix,
                "Acme Cloud " + suffix,
                "API outage",
                "investigating",
                "major",
                null,
                "Elevated errors",
                Instant.now(),
                null,
                null));

        mvc.perform(get("/api/matching/change-signals/{id}", incident.id()).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.signalType").value("VENDOR_OUTAGE"))
                .andExpect(jsonPath("$.matchedComponentCount").value(0))
                .andExpect(jsonPath("$.matchedVendorCount").value(1))
                .andExpect(jsonPath("$.impactedServices.length()").value(1))
                .andExpect(jsonPath("$.impactedServices[0].service.id").value(service.getId().toString()))
                .andExpect(jsonPath("$.impactedServices[0].owners[0].id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].kind").value("VENDOR_DEPENDENCY"))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].vendor.id")
                        .value(vendor.getId().toString()))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].componentPath.length()").value(0));
    }

    @Test
    void surfacesAnAmbiguousBareNameMatchButFlagsItAsPossible() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService service = inventory.createBusinessService("Reports " + suffix, BusinessCriticality.MEDIUM);
        // A bare package name with no namespace — the CPE cannot prove the ecosystem/vendor.
        SoftwareComponent bare = inventory.createSoftwareComponent("npm", "widget-" + suffix, "1.0.0");
        inventory.addServiceComponent(service.getId(), bare.getId());

        String cve = "CVE-2099-" + Math.abs(UUID.randomUUID().getMostSignificantBits());
        Advisory advisory = advisories.upsert(new Advisory(
                null, "nvd", cve, "ambiguous match", Instant.now(), Instant.now(), null,
                List.of(new AffectedProduct(
                        "cpe:2.3:a:acme:widget-" + suffix + ":*:*:*:*:*:*:*:*",
                        "a", "acme", "widget-" + suffix, null, null, null, null, null))));

        mvc.perform(get("/api/matching/change-signals/{id}", advisory.id()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedComponentCount").value(1))
                .andExpect(jsonPath("$.impactedServices.length()").value(1))
                .andExpect(jsonPath("$.impactedServices[0].service.id").value(service.getId().toString()))
                .andExpect(jsonPath("$.impactedServices[0].confidence").value("POSSIBLE"))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].confidence").value("POSSIBLE"))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].affectedProduct.identityMatch")
                        .value("AMBIGUOUS_NAME"))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].explanation")
                        .value(org.hamcrest.Matchers.containsString("possible match")));
    }

    @Test
    void matchesAnOsvPurlAdvisoryToAnEcosystemComponent() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService service = inventory.createBusinessService("Web " + suffix, BusinessCriticality.HIGH);
        Owner owner = inventory.createOwner("Web team " + suffix);
        inventory.addServiceOwner(service.getId(), owner.getId());
        // An npm component the OSV advisory covers, and a same-name PyPI component it must not touch.
        SoftwareComponent axios = inventory.createSoftwareComponent("npm", "axios-" + suffix, "1.5.0");
        inventory.createSoftwareComponent("pypi", "axios-" + suffix, "1.5.0");
        inventory.addServiceComponent(service.getId(), axios.getId());

        String cve = "CVE-2099-" + Math.abs(UUID.randomUUID().getMostSignificantBits());
        // OSV reports it as a GHSA aliasing the CVE, with a purl-shaped affected range [.., 1.6.0).
        Advisory advisory = advisories.upsert(new Advisory(
                null, "osv", "GHSA-" + suffix, cve, "axios SSRF", Instant.now(), Instant.now(), null,
                List.of(AffectedProduct.ofPackage("npm", "axios-" + suffix, null, null, null, null, "1.6.0"))));

        mvc.perform(get("/api/matching/change-signals/{id}", advisory.id()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.title").value(cve)) // the canonical CVE, not the GHSA id
                .andExpect(jsonPath("$.matchedComponentCount").value(1)) // the npm one only, not PyPI
                .andExpect(jsonPath("$.impactedServices.length()").value(1))
                .andExpect(jsonPath("$.impactedServices[0].service.id").value(service.getId().toString()))
                .andExpect(jsonPath("$.impactedServices[0].confidence").value("CONFIRMED"))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].affectedProduct.identityMatch")
                        .value("EXACT_PACKAGE"))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].affectedProduct.versionMatch")
                        .value("IN_RANGE"));
    }

    @Test
    void requiresADomainReadRoleAndUsesTheUniformNotFoundContract() throws Exception {
        UUID unknown = UUID.randomUUID();

        mvc.perform(get("/api/matching/change-signals/{id}", unknown))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/matching/change-signals/{id}", unknown).with(role("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/matching/change-signals/{id}", unknown).with(role("COMPLIANCE_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/matching/change-signals/" + unknown));
    }

    private static RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
