package com.aegis.core.inventory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.platform.identity.DirectoryUser;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.FakeIdentityDirectory;
import com.aegis.core.support.TestOwners;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The recommended RBAC model (ADR §6d): governance roles ({@code ANALYST}, {@code COMPLIANCE_ADMIN})
 * register services, set their risk attributes and assign their owners; an owner is a real organization
 * member holding {@code SERVICE_OWNER}, verified at assignment; and that owner may keep <em>their own</em>
 * services' composition current (SBOM, components, vendors) — but not anyone else's, and never re-grade
 * or re-staff a service.
 */
class ServiceOwnershipIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String SBOM_FIXTURE = "fixtures/sbom/storefront.cdx.json";

    @Autowired
    private InventoryService inventory;

    @Autowired
    private TestOwners owners;

    @Autowired
    private FakeIdentityDirectory directory;

    // ---- Assignment: owners are verified organization members -------------------------------------

    @Test
    void governanceAssignsAServiceOwnerMemberByUserId() throws Exception {
        BusinessService service = newService("Assign");
        DirectoryUser member = directory.member("Jane Owner", InventoryRoles.SERVICE_OWNER);

        mvc.perform(assign(service.getId(), member.id()).with(role("ANALYST")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(member.id().toString()))
                .andExpect(jsonPath("$.name").value("Jane Owner"))
                .andExpect(jsonPath("$.email").value(member.email()));

        mvc.perform(get("/api/inventory/services/{id}/owners", service.getId()).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].userId", hasItem(member.id().toString())));
    }

    @Test
    void refusesAMemberWithoutTheServiceOwnerRoleAnUnknownUserAndADisabledAccount() throws Exception {
        BusinessService service = newService("Refuse");
        DirectoryUser analyst = directory.member("Only An Analyst", "ANALYST");
        DirectoryUser disabled = directory.register(new DirectoryUser(
                UUID.randomUUID(), "gone@example.com", "Gone Owner", Set.of(InventoryRoles.SERVICE_OWNER), false));

        mvc.perform(assign(service.getId(), analyst.id()).with(role("ANALYST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("does not hold the Service Owner role")));
        // Not a member of the caller's organization (identity-service answers 404 for those).
        mvc.perform(assign(service.getId(), UUID.randomUUID()).with(role("ANALYST")))
                .andExpect(status().isNotFound());
        mvc.perform(assign(service.getId(), disabled.id()).with(role("COMPLIANCE_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("disabled")));
    }

    @Test
    void rejectsAssigningTheSamePersonTwiceAndAMissingUserId() throws Exception {
        BusinessService service = newService("Twice");
        Owner owner = owners.assign(service.getId(), "Twice Owner");

        mvc.perform(assign(service.getId(), owner.getUserId()).with(role("ANALYST")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/inventory/services/{id}/owners", service.getId()).with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownersCannotBeCreatedByNameAnyMore() throws Exception {
        mvc.perform(post("/api/inventory/owners").with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Payments Team\"}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void reassignmentRefreshesTheDisplaySnapshotFromIdentity() {
        BusinessService first = newService("Snapshot one");
        BusinessService second = newService("Snapshot two");
        DirectoryUser member = directory.member("Old Name", InventoryRoles.SERVICE_OWNER);
        Owner owner = owners.assign(first.getId(), member);

        directory.register(new DirectoryUser(
                member.id(), "renamed@example.com", "New Name", member.roles(), true));
        Owner again = owners.assign(second.getId(), member);

        assertThat(again.getId()).isEqualTo(owner.getId());
        assertThat(inventory.getOwner(owner.getId()).getName()).isEqualTo("New Name");
        assertThat(inventory.getOwner(owner.getId()).getEmail())
                .isEqualTo("renamed@example.com");
    }

    // ---- Owner lifecycle: the owners list is exactly who is accountable ----------------------------

    @Test
    void anOwnerLeftOwningNothingIsRemovedButOneWithOtherServicesStays() throws Exception {
        BusinessService kept = newService("Kept");
        BusinessService dropped = newService("Dropped");
        DirectoryUser shared = directory.member("Shared Owner", InventoryRoles.SERVICE_OWNER);
        Owner sharedOwner = owners.assign(kept.getId(), shared);
        owners.assign(dropped.getId(), shared);
        Owner soloOwner = owners.assign(dropped.getId(), "Solo Owner");

        mvc.perform(delete("/api/inventory/services/{sid}/owners/{oid}", dropped.getId(), sharedOwner.getId())
                        .with(role("ANALYST")))
                .andExpect(status().isNoContent());
        // Still owns `kept`, so still an owner.
        mvc.perform(get("/api/inventory/owners/{id}", sharedOwner.getId()).with(role("ANALYST")))
                .andExpect(status().isOk());

        // Deleting the only service Solo owned removes Solo as an owner too.
        inventory.deleteBusinessService(dropped.getId());
        mvc.perform(get("/api/inventory/owners/{id}", soloOwner.getId()).with(role("ANALYST")))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/inventory/services/{sid}/owners/{oid}", kept.getId(), sharedOwner.getId())
                        .with(role("ANALYST")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/inventory/owners/{id}", sharedOwner.getId()).with(role("ANALYST")))
                .andExpect(status().isNotFound());
    }

    // ---- Owner-scoped composition: an owner maintains their own services only -----------------------

    @Test
    void anAssignedOwnerMaintainsTheirOwnServicesCompositionButNoOneElses() throws Exception {
        BusinessService mine = newService("Mine");
        BusinessService theirs = newService("Theirs");
        Owner me = owners.assign(mine.getId(), "Composing Owner");
        owners.assign(theirs.getId(), "Someone Else");
        ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Vendor " + UUID.randomUUID());

        // My service: link and unlink a vendor, import an SBOM.
        mvc.perform(post("/api/inventory/services/{sid}/vendors/{vid}", mine.getId(), vendor.getId())
                        .with(serviceOwner(me.getUserId())))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/inventory/services/{sid}/vendors/{vid}", mine.getId(), vendor.getId())
                        .with(serviceOwner(me.getUserId())))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/inventory/services/{sid}/sbom", mine.getId()).with(serviceOwner(me.getUserId()))
                        .contentType(MediaType.APPLICATION_JSON).content(sbom()))
                .andExpect(status().isOk());

        // Someone else's service: refused.
        mvc.perform(post("/api/inventory/services/{sid}/vendors/{vid}", theirs.getId(), vendor.getId())
                        .with(serviceOwner(me.getUserId())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/services/{sid}/sbom", theirs.getId()).with(serviceOwner(me.getUserId()))
                        .contentType(MediaType.APPLICATION_JSON).content(sbom()))
                .andExpect(status().isForbidden());

        // Governance may compose any service.
        mvc.perform(post("/api/inventory/services/{sid}/vendors/{vid}", theirs.getId(), vendor.getId())
                        .with(role("COMPLIANCE_ADMIN")))
                .andExpect(status().isCreated());
    }

    @Test
    void anOwnerCannotRegisterReGradeOrReStaffAService() throws Exception {
        BusinessService mine = newService("Governed");
        Owner me = owners.assign(mine.getId(), "Governed Owner");
        DirectoryUser colleague = directory.member("Colleague", InventoryRoles.SERVICE_OWNER);

        mvc.perform(post("/api/inventory/services").with(serviceOwner(me.getUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Self-registered " + UUID.randomUUID() + "\",\"criticality\":\"LOW\"}"))
                .andExpect(status().isForbidden());
        // Criticality/exposure drive the score — an owner may not lower their own.
        mvc.perform(put("/api/inventory/services/{id}", mine.getId()).with(serviceOwner(me.getUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + mine.getName() + "\",\"criticality\":\"LOW\",\"exposure\":\"ISOLATED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(assign(mine.getId(), colleague.id()).with(serviceOwner(me.getUserId())))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/inventory/services/{sid}/owners/{oid}", mine.getId(), me.getId())
                        .with(serviceOwner(me.getUserId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAssignmentAloneIsNotEnoughWithoutTheServiceOwnerRoleInTheToken() throws Exception {
        BusinessService mine = newService("Role revoked");
        Owner me = owners.assign(mine.getId(), "Revoked Owner");
        ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Vendor " + UUID.randomUUID());

        // Still assigned in the inventory, but the token no longer carries SERVICE_OWNER.
        mvc.perform(post("/api/inventory/services/{sid}/vendors/{vid}", mine.getId(), vendor.getId())
                        .with(jwt().jwt(token -> token.subject(me.getUserId().toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void theOwnersListShowsAssignedPeopleOnly() throws Exception {
        // The suite shares one database, so narrow the listing to this test's own people.
        String tag = UUID.randomUUID().toString();
        BusinessService service = newService("Listed");
        Owner listed = owners.assign(service.getId(), "Listed " + tag);
        DirectoryUser unassigned = directory.member("Unassigned " + tag, InventoryRoles.SERVICE_OWNER);

        mvc.perform(get("/api/inventory/owners").param("search", tag).param("size", "50")
                        .with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].userId", hasItem(listed.getUserId().toString())))
                .andExpect(jsonPath("$.content[*].userId", not(hasItem(unassigned.id().toString()))));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private BusinessService newService(String label) {
        return inventory.createBusinessService(label + " " + UUID.randomUUID(), BusinessCriticality.HIGH);
    }

    private static MockHttpServletRequestBuilder assign(
            UUID serviceId, UUID userId) {
        return post("/api/inventory/services/{id}/owners", serviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}");
    }

    /** A caller holding one domain role (its subject is irrelevant to role-only checks). */
    private static RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /** A SERVICE_OWNER whose token subject is their identity-service user id — what ownership keys on. */
    private static RequestPostProcessor serviceOwner(UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE_OWNER"));
    }

    private static String sbom() throws IOException {
        try (InputStream in = ServiceOwnershipIntegrationTest.class.getClassLoader().getResourceAsStream(SBOM_FIXTURE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
