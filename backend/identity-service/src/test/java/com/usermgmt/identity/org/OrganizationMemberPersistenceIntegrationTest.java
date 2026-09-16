package com.usermgmt.identity.org;

import java.util.Set;
import java.util.UUID;

import com.usermgmt.identity.support.AbstractIntegrationTest;
import com.usermgmt.identity.user.Role;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 (ADR 0001): a user belongs to an organization through a membership that carries its own
 * org-scoped roles; one user may hold many memberships, but at most one per org.
 */
class OrganizationMemberPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private OrganizationMemberRepository members;

    @Autowired
    private UserRepository users;

    private UUID newUser() {
        User u = new User(UUID.randomUUID(), "m-" + UUID.randomUUID() + "@example.com", "x", "Member");
        return users.save(u).getId();
    }

    private Organization newOrg() {
        return organizations.save(new Organization(UUID.randomUUID(), "Org " + UUID.randomUUID()));
    }

    @Test
    void persistsAMembershipWithItsRolesAndDefaultActiveStatus() {
        Organization org = newOrg();
        UUID userId = newUser();

        OrganizationMember member = new OrganizationMember(UUID.randomUUID(), org.getId(), userId);
        member.setRoles(Set.of(Role.ORG_ADMIN, Role.ANALYST));
        members.save(member);

        OrganizationMember reloaded = members.findByOrgIdAndUserId(org.getId(), userId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(reloaded.getRoles()).containsExactlyInAnyOrder(Role.ORG_ADMIN, Role.ANALYST);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(members.existsByOrgIdAndUserId(org.getId(), userId)).isTrue();
    }

    @Test
    void letsOneUserBelongToManyOrgs() {
        UUID userId = newUser();
        Organization a = newOrg();
        Organization b = newOrg();
        members.save(new OrganizationMember(UUID.randomUUID(), a.getId(), userId));
        members.save(new OrganizationMember(UUID.randomUUID(), b.getId(), userId));

        assertThat(members.findByUserId(userId))
                .hasSize(2)
                .extracting(OrganizationMember::getOrgId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    void rejectsADuplicateMembershipForTheSameUserAndOrg() {
        Organization org = newOrg();
        UUID userId = newUser();
        members.save(new OrganizationMember(UUID.randomUUID(), org.getId(), userId));

        OrganizationMember duplicate = new OrganizationMember(UUID.randomUUID(), org.getId(), userId);
        assertThatThrownBy(() -> members.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
