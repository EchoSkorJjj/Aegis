package com.aegis.core.notifications;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
import com.aegis.core.notifications.OwnerNotification.EmailStatus;
import com.aegis.core.platform.identity.IdentityInternalApi.OwnerEmailItem;
import com.aegis.core.platform.scheduling.SchedulerLease;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractIntegrationTest;
import com.aegis.core.support.FakeIdentityInternalApi;
import com.aegis.core.support.TestOwners;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Owner notifications (E9 story 5), in a fresh organization per test. The estate (as the triage queue's test): a
 * CRITICAL internet-facing service with no owner, reaching a vulnerable library through another package, and a
 * HIGH isolated service with one owner, using the library directly and depending on a vendor in an active outage.
 * So the owner has two items to decide — the outage and the library — and the unowned service's item tells nobody.
 */
class OwnerNotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private CurrentOrg currentOrg;
    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private VendorIncidentStore incidents;
    @Autowired private TestOwners owners;
    @Autowired private OwnerNotifier notifier;
    @Autowired private OwnerNotificationStore store;
    @Autowired private FakeIdentityInternalApi identity;
    @Autowired private SchedulerLease lease;

    private UUID org;
    private String tag;
    private BusinessService internalService;
    private Owner owner;
    private Advisory advisory;
    private VendorIncident outage;

    @BeforeEach
    void estate() {
        org = UUID.randomUUID();
        tag = UUID.randomUUID().toString();
        String library = "notify-lib-" + tag;
        inOrg(() -> {
            BusinessService publicService = inventory.createBusinessService("Public " + tag,
                    BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
            internalService = inventory.createBusinessService("Internal " + tag,
                    BusinessCriticality.HIGH, ServiceExposure.ISOLATED);
            owner = owners.assign(internalService.getId(), "Owner " + tag);
            SoftwareComponent direct = inventory.createSoftwareComponent("npm", "direct-" + tag, "1.0.0");
            SoftwareComponent vulnerable = inventory.createSoftwareComponent("npm", library, "1.0.0");
            inventory.addComponentDependency(direct.getId(), vulnerable.getId());
            inventory.addServiceComponent(publicService.getId(), direct.getId());
            inventory.addServiceComponent(internalService.getId(), vulnerable.getId());
            ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Cloud " + tag);
            inventory.addServiceVendor(internalService.getId(), vendor.getId());
            return null;
        });
        advisory = advisory(library, "CVE-2097-" + tag);
        outage = incidents.upsert(new VendorIncident(null, "vendor-status", "outage-" + tag, "cloud-" + tag,
                "Cloud " + tag, "API outage", "investigating", "major", null, "Elevated errors", Instant.now(), null,
                null));
    }

    @Test
    void eachOwnerIsToldOnceInTheAppAndByEmailAboutEachItemThatNeedsTheirDecision() {
        OwnerNotifier.Run first = inOrg(notifier::notifyOwners);

        assertThat(first.notified()).isEqualTo(2);
        List<OwnerNotification> mine = notificationsOf(owner.getUserId());
        assertThat(mine).extracting(OwnerNotification::changeSignalId)
                .containsExactlyInAnyOrder(advisory.id(), outage.id());
        assertThat(mine).allSatisfy(notification -> {
            assertThat(notification.serviceId()).isEqualTo(internalService.getId());
            assertThat(notification.emailStatus()).isEqualTo(EmailStatus.SENT);
            assertThat(notification.emailedAt()).isNotNull();
            assertThat(notification.readAt()).isNull();
        });
        // One email, with both items, each linking to its page in the app.
        assertThat(identity.sentTo(owner.getUserId())).singleElement().satisfies(email -> {
            assertThat(email.orgId()).isEqualTo(org);
            assertThat(email.items()).containsExactlyInAnyOrder(
                    new OwnerEmailItem("CVE-2097-" + tag + " in notify-lib-" + tag + " 1.0.0",
                            "Internal " + tag, "/findings/" + advisory.id()),
                    new OwnerEmailItem("API outage at Cloud " + tag, "Internal " + tag, "/findings/" + outage.id()));
        });

        // Looking again finds the same items: nobody is told twice.
        OwnerNotifier.Run second = inOrg(notifier::notifyOwners);
        assertThat(second).isEqualTo(new OwnerNotifier.Run(0, 0));
        assertThat(notificationsOf(owner.getUserId())).hasSize(2);
        assertThat(identity.sentTo(owner.getUserId())).hasSize(1);
    }

    @Test
    void anItemThatAppearsLaterIsEmailedOnItsOwn() {
        inOrg(notifier::notifyOwners);
        inOrg(() -> {
            SoftwareComponent other = inventory.createSoftwareComponent("npm", "later-lib-" + tag, "1.0.0");
            inventory.addServiceComponent(internalService.getId(), other.getId());
            return null;
        });
        Advisory later = advisory("later-lib-" + tag, "CVE-2096-" + tag);

        inOrg(notifier::notifyOwners);

        assertThat(identity.sentTo(owner.getUserId())).hasSize(2);
        assertThat(identity.sentTo(owner.getUserId()).get(1).items()).extracting(OwnerEmailItem::path)
                .containsExactly("/findings/" + later.id());
    }

    @Test
    void aNewOwnerIsToldAboutWhatIsAlreadyWaitingAndThePreviousOneIsNotToldAgain() {
        inOrg(notifier::notifyOwners);
        Owner successor = inOrg(() -> {
            Owner assigned = owners.assign(internalService.getId(), "Successor " + tag);
            inventory.removeServiceOwner(internalService.getId(), owner.getId());
            return assigned;
        });

        inOrg(notifier::notifyOwners);

        assertThat(identity.sentTo(successor.getUserId())).singleElement()
                .satisfies(email -> assertThat(email.items()).hasSize(2));
        assertThat(identity.sentTo(owner.getUserId())).hasSize(1);
    }

    @Test
    void anItemAlreadyDecidedIsNotNotifiedButOneInReviewIs() throws Exception {
        // The outage's response is dismissed (Closed) before Aegis looks; the library's is drafted (In review).
        String dismissed = JsonPath.read(mvc.perform(post("/api/actions/risk-tickets").with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft(outage.id())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/actions/risk-tickets/{id}/dismiss", dismissed).with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"The vendor is not used here\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/actions/risk-tickets").with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(draft(advisory.id())))
                .andExpect(status().isCreated());

        inOrg(notifier::notifyOwners);

        assertThat(notificationsOf(owner.getUserId())).extracting(OwnerNotification::changeSignalId)
                .containsExactly(advisory.id());
    }

    @Test
    void aFailedEmailIsTriedAgainOnTheNextRunAndStillSentOnce() {
        identity.failNext(owner.getUserId(), 1);

        inOrg(notifier::notifyOwners);
        assertThat(notificationsOf(owner.getUserId())).extracting(OwnerNotification::emailStatus)
                .containsOnly(EmailStatus.PENDING);
        assertThat(identity.sentTo(owner.getUserId())).isEmpty();

        inOrg(notifier::notifyOwners);
        inOrg(notifier::notifyOwners);
        assertThat(identity.sentTo(owner.getUserId())).singleElement()
                .satisfies(email -> assertThat(email.items()).hasSize(2));
        assertThat(notificationsOf(owner.getUserId())).extracting(OwnerNotification::emailStatus)
                .containsOnly(EmailStatus.SENT);
    }

    @Test
    void anEmailThatKeepsFailingIsGivenUpAfterThreeTries() {
        identity.failNext(owner.getUserId(), OwnerNotifier.MAX_EMAIL_ATTEMPTS + 5);
        for (int run = 0; run < OwnerNotifier.MAX_EMAIL_ATTEMPTS + 2; run++) {
            inOrg(notifier::notifyOwners);
        }
        assertThat(notificationsOf(owner.getUserId())).extracting(OwnerNotification::emailStatus)
                .containsOnly(EmailStatus.FAILED);
        assertThat(identity.sentTo(owner.getUserId())).isEmpty();
    }

    @Test
    void anOwnerIdentityServiceWontEmailStillSeesTheItemsInTheApp() {
        identity.unreachable(owner.getUserId());
        inOrg(notifier::notifyOwners);
        inOrg(notifier::notifyOwners);

        assertThat(notificationsOf(owner.getUserId())).hasSize(2).extracting(OwnerNotification::emailStatus)
                .containsOnly(EmailStatus.UNREACHABLE);
        assertThat(identity.sentTo(owner.getUserId())).isEmpty();
    }

    @Test
    void theScheduledJobNotifiesEveryOrganizationIdentityServiceKnows() {
        identity.organizations(List.of(UUID.randomUUID(), org));
        new OwnerNotificationJob(notifier, identity, currentOrg, lease).run();
        assertThat(identity.sentTo(owner.getUserId())).hasSize(1);
    }

    @Test
    void ownersReadOnlyTheirOwnNotificationsAndMarkThemRead() throws Exception {
        inOrg(notifier::notifyOwners);
        UUID ownerId = owner.getUserId();

        String page = mvc.perform(get("/api/notifications").with(as("SERVICE_OWNER", ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].changeSignalId")
                        .value(containsInAnyOrder(advisory.id().toString(), outage.id().toString())))
                .andExpect(jsonPath("$.content[0].serviceName").value("Internal " + tag))
                .andExpect(jsonPath("$.content[0].emailStatus").value("SENT"))
                .andReturn().getResponse().getContentAsString();
        String first = JsonPath.read(page, "$.content[0].id");
        mvc.perform(get("/api/notifications/unread-count").with(as("SERVICE_OWNER", ownerId)))
                .andExpect(jsonPath("$.unread").value(2));

        // Nobody else reads them or marks them, in this organization or another.
        UUID someoneElse = UUID.randomUUID();
        mvc.perform(get("/api/notifications").with(as("ANALYST", someoneElse)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post("/api/notifications/{id}/read", first).with(as("ANALYST", someoneElse)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/notifications").with(as("SERVICE_OWNER", ownerId, UUID.randomUUID())))
                .andExpect(jsonPath("$.totalElements").value(0));
        for (String role : List.of("USER", "ORG_ADMIN")) {
            mvc.perform(get("/api/notifications").with(as(role, ownerId))).andExpect(status().isForbidden());
        }

        mvc.perform(post("/api/notifications/{id}/read", first).with(as("SERVICE_OWNER", ownerId)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications").param("unread", "true").with(as("SERVICE_OWNER", ownerId)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(post("/api/notifications/read-all").with(as("SERVICE_OWNER", ownerId)))
                .andExpect(jsonPath("$.marked").value(1));
        mvc.perform(get("/api/notifications/unread-count").with(as("SERVICE_OWNER", ownerId)))
                .andExpect(jsonPath("$.unread").value(0));
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private List<OwnerNotification> notificationsOf(UUID userId) {
        return inOrg(() -> store.findForUser(userId, false, PageRequest.of(0, 50)).getContent());
    }

    private <T> T inOrg(Supplier<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        currentOrg.runAs(org, () -> result.set(work.get()));
        return result.get();
    }

    private Advisory advisory(String library, String cve) {
        Instant old = Instant.parse("2021-01-01T00:00:00Z");
        return advisories.upsert(new Advisory(null, "nvd", cve, "Notify " + cve, old, old,
                new Advisory.Cvss("3.1", null, new BigDecimal("10.0"), "CRITICAL"),
                List.of(AffectedProduct.ofPackage("npm", library, null, "0", null, null, "2.0.0"))));
    }

    private String draft(UUID changeSignalId) {
        return "{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}".formatted(changeSignalId, internalService.getId());
    }

    private RequestPostProcessor as(String role, UUID user) {
        return as(role, user, org);
    }

    private static RequestPostProcessor as(String role, UUID user, UUID org) {
        return jwt().jwt(builder -> builder.subject(user.toString()).claim("org", org.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
