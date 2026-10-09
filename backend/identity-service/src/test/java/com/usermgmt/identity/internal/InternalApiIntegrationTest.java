package com.usermgmt.identity.internal;

import java.util.UUID;

import com.usermgmt.identity.auth.email.OwnerNotification;
import com.usermgmt.identity.org.MembershipStatus;
import com.usermgmt.identity.org.Organization;
import com.usermgmt.identity.org.OrganizationMember;
import com.usermgmt.identity.org.OrganizationMemberRepository;
import com.usermgmt.identity.org.OrganizationRepository;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import com.usermgmt.identity.support.CapturingEmailSender;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The internal API aegis-core's owner notifications call (E9 story 5): only with the service token, never with
 * a user's token, and an email only to an active, reachable member of the named organization.
 */
@TestPropertySource(properties = "identity.internal.service-token=" + InternalApiIntegrationTest.TOKEN)
class InternalApiIntegrationTest extends AbstractIntegrationTest {

    static final String TOKEN = "internal-test-token";
    private static final String EMAIL_PATH = "/internal/notifications/owner-email";

    @Autowired private OrganizationRepository organizations;
    @Autowired private OrganizationMemberRepository members;
    @Autowired private UserRepository users;
    @Autowired private CapturingEmailSender emails;

    private Organization org;

    @BeforeEach
    void organization() {
        org = organizations.save(new Organization(UUID.randomUUID(), "Internal " + UUID.randomUUID()));
    }

    @Test
    void refusesAnyCallerWithoutTheServiceToken() throws Exception {
        mvc.perform(get("/internal/organizations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/organizations").header(InternalSecurityConfig.HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
        // A user's bearer token is not the service token.
        mvc.perform(get("/internal/organizations").header("Authorization", "Bearer not-a-service-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(EMAIL_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(request(org.getId(), UUID.randomUUID(), "/findings/abc")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listsEveryOrganization() throws Exception {
        mvc.perform(get("/internal/organizations").header(InternalSecurityConfig.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasItem(org.getId().toString())));
    }

    @Test
    void emailsAnActiveMemberTheItemsThatNeedTheirDecision() throws Exception {
        User owner = member(MembershipStatus.ACTIVE, true, true);

        mvc.perform(post(EMAIL_PATH).header(InternalSecurityConfig.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(org.getId(), owner.getId(), "/findings/1b2c-3d")))
                .andExpect(status().isNoContent());

        assertThat(emails.ownerNotificationsFor(owner.getEmail())).singleElement().satisfies(sent ->
                assertThat(sent.items()).containsExactly(
                        new OwnerNotification.Item("CVE-2099-1 in log4j-core 2.14.1", "Payments API", "/findings/1b2c-3d")));
    }

    @Test
    void sendsEachDeliveryOnceHoweverOftenItIsAskedFor() throws Exception {
        User owner = member(MembershipStatus.ACTIVE, true, true);
        UUID delivery = UUID.randomUUID();

        // aegis-core asks again under the same id when it couldn't tell whether the first request went through.
        for (int asked = 0; asked < 2; asked++) {
            mvc.perform(post(EMAIL_PATH).header(InternalSecurityConfig.HEADER, TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request(org.getId(), owner.getId(), delivery, "/findings/1b2c-3d")))
                    .andExpect(status().isNoContent());
        }
        assertThat(emails.ownerNotificationsFor(owner.getEmail())).hasSize(1);

        mvc.perform(post(EMAIL_PATH).header(InternalSecurityConfig.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(org.getId(), owner.getId(), "/findings/1b2c-3d")))
                .andExpect(status().isNoContent());
        assertThat(emails.ownerNotificationsFor(owner.getEmail())).hasSize(2);
    }

    @Test
    void aDeliveryNotSentCanStillBeSentLater() throws Exception {
        User joining = member(MembershipStatus.INVITED, true, true);
        UUID delivery = UUID.randomUUID();
        mvc.perform(post(EMAIL_PATH).header(InternalSecurityConfig.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(org.getId(), joining.getId(), delivery, "/findings/x")))
                .andExpect(status().isNotFound());

        OrganizationMember membership = members.findByOrgIdAndUserId(org.getId(), joining.getId()).orElseThrow();
        membership.setStatus(MembershipStatus.ACTIVE);
        members.save(membership);
        mvc.perform(post(EMAIL_PATH).header(InternalSecurityConfig.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(org.getId(), joining.getId(), delivery, "/findings/x")))
                .andExpect(status().isNoContent());
        assertThat(emails.ownerNotificationsFor(joining.getEmail())).hasSize(1);
    }

    @Test
    void emailsNobodyOutsideTheOrganizationOrUnreachable() throws Exception {
        User stranger = member(null, true, true);
        User suspended = member(MembershipStatus.SUSPENDED, true, true);
        User invited = member(MembershipStatus.INVITED, true, true);
        User disabled = member(MembershipStatus.ACTIVE, false, true);
        User unverified = member(MembershipStatus.ACTIVE, true, false);

        for (User user : new User[]{stranger, suspended, invited}) {
            mvc.perform(post(EMAIL_PATH).header(InternalSecurityConfig.HEADER, TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request(org.getId(), user.getId(), "/findings/x")))
                    .andExpect(status().isNotFound());
        }
        for (User user : new User[]{disabled, unverified}) {
            mvc.perform(post(EMAIL_PATH).header(InternalSecurityConfig.HEADER, TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request(org.getId(), user.getId(), "/findings/x")))
                    .andExpect(status().isConflict());
        }
        for (User user : new User[]{stranger, suspended, invited, disabled, unverified}) {
            assertThat(emails.ownerNotificationsFor(user.getEmail())).isEmpty();
        }
    }

    @Test
    void anItemLinksOnlyToAPathInTheApp() throws Exception {
        User owner = member(MembershipStatus.ACTIVE, true, true);
        for (String path : new String[]{"https://evil.example/phish", "//evil.example", "/findings?x=1", "findings/1",
                "/findings/1#top"}) {
            mvc.perform(post(EMAIL_PATH).header(InternalSecurityConfig.HEADER, TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request(org.getId(), owner.getId(), path)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(EMAIL_PATH).header(InternalSecurityConfig.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":\"%s\",\"userId\":\"%s\",\"deliveryId\":\"%s\",\"items\":[]}"
                                .formatted(org.getId(), owner.getId(), UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        // Every email is named, so a repeat can be recognised.
        mvc.perform(post(EMAIL_PATH).header(InternalSecurityConfig.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(org.getId(), owner.getId(), null, "/findings/1")))
                .andExpect(status().isBadRequest());
        assertThat(emails.ownerNotificationsFor(owner.getEmail())).isEmpty();
    }

    /** A user with a membership in {@code org} ({@code status}; null for none). */
    private User member(MembershipStatus status, boolean enabled, boolean verified) {
        User user = new User(UUID.randomUUID(), "owner-" + UUID.randomUUID() + "@example.test", "unused", "Owner");
        user.setEnabled(enabled);
        user.setEmailVerified(verified);
        user = users.save(user);
        if (status != null) {
            OrganizationMember membership = new OrganizationMember(UUID.randomUUID(), org.getId(), user.getId());
            membership.setStatus(status);
            members.save(membership);
        }
        return user;
    }

    private static String request(UUID orgId, UUID userId, String path) {
        return request(orgId, userId, UUID.randomUUID(), path);
    }

    private static String request(UUID orgId, UUID userId, UUID deliveryId, String path) {
        return """
                {"orgId":"%s","userId":"%s","deliveryId":%s,"items":[
                  {"title":"CVE-2099-1 in log4j-core 2.14.1","serviceName":"Payments API","path":"%s"}]}
                """.formatted(orgId, userId, deliveryId == null ? "null" : "\"" + deliveryId + "\"", path);
    }
}
