package com.usermgmt.identity.org;

import java.util.UUID;

import com.usermgmt.identity.support.AbstractIntegrationTest;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 1 (ADR 0001): the organization table persists and a user can be tied to one. */
class OrganizationPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Test
    void persistsAnOrganizationWithServerTimestamps() {
        Organization saved = organizations.save(new Organization(UUID.randomUUID(), "Acme Bank"));

        Organization reloaded = organizations.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Acme Bank");
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void tiesAUserToItsOrganizationAndLeavesThePlatformAdminOrgLess() {
        Organization org =
                organizations.save(new Organization(UUID.randomUUID(), "Payments Co " + UUID.randomUUID()));

        User member = new User(UUID.randomUUID(), "member-" + UUID.randomUUID() + "@example.com", "x", "Member");
        member.setLastActiveOrgId(org.getId());
        users.save(member);

        User platformAdmin = new User(UUID.randomUUID(), "ops-" + UUID.randomUUID() + "@example.com", "x", "Ops");
        users.save(platformAdmin);

        assertThat(users.findById(member.getId()).orElseThrow().getLastActiveOrgId()).isEqualTo(org.getId());
        assertThat(users.findById(platformAdmin.getId()).orElseThrow().getLastActiveOrgId()).isNull();
    }
}
