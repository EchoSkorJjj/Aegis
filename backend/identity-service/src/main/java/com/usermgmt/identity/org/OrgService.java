package com.usermgmt.identity.org;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.usermgmt.identity.common.ApiExceptions;
import com.usermgmt.identity.org.OrgDtos.OrgMembershipResponse;
import com.usermgmt.identity.user.Role;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Organization membership queries and self-serve org creation (ADR 0001). */
@Service
public class OrgService {

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final UserRepository users;
    private final boolean orgSignupEnabled;

    public OrgService(OrganizationRepository organizations,
                      OrganizationMemberRepository members,
                      UserRepository users,
                      @Value("${identity.org-signup.enabled:true}") boolean orgSignupEnabled) {
        this.organizations = organizations;
        this.members = members;
        this.users = users;
        this.orgSignupEnabled = orgSignupEnabled;
    }

    /** Every organization the caller belongs to, with the roles they hold in each. */
    @Transactional(readOnly = true)
    public List<OrgMembershipResponse> myOrganizations(UUID userId) {
        return members.findByUserId(userId).stream()
                .map(m -> new OrgMembershipResponse(
                        m.getOrgId(),
                        organizations.findById(m.getOrgId()).map(Organization::getName).orElse(null),
                        m.getRoles(),
                        m.getStatus()))
                .toList();
    }

    /**
     * Creates an organization and makes the caller its owner ({@code ORG_ADMIN}), gated by
     * {@code ORG_SIGNUP_ENABLED}. It becomes the caller's remembered active org; the caller must
     * {@code POST /auth/switch-org} to obtain a token actually scoped to it.
     */
    @Transactional
    public OrgMembershipResponse createOrganization(UUID userId, String name) {
        if (!orgSignupEnabled) {
            throw new ApiExceptions.Forbidden("Organization signup is disabled");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiExceptions.Unauthorized("Not authenticated"));
        Organization org = organizations.save(new Organization(UUID.randomUUID(), name.trim()));
        OrganizationMember membership = new OrganizationMember(UUID.randomUUID(), org.getId(), userId);
        membership.setRoles(Set.of(Role.ORG_ADMIN));
        members.save(membership);
        // Make the new org the caller's active workspace. ORG_ADMIN lives on the membership only —
        // user_roles is platform-scoped (ADR 0001 §4/§5) — so the caller must POST /auth/switch-org
        // to obtain a token actually scoped to it.
        user.setLastActiveOrgId(org.getId());
        return new OrgMembershipResponse(org.getId(), org.getName(), membership.getRoles(), membership.getStatus());
    }
}
