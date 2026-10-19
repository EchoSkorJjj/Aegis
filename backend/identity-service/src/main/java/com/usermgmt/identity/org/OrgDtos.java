package com.usermgmt.identity.org;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.usermgmt.identity.user.Role;

public final class OrgDtos {

    private OrgDtos() {
    }

    /** Create a new organization the caller will own (ADR 0001). */
    public record CreateOrgRequest(@NotBlank @Size(max = 255) String name) {
    }

    /**
     * One organization the caller belongs to, with the roles they hold in it — drives the workspace
     * switcher and is returned when an org is created.
     */
    public record OrgMembershipResponse(UUID id, String name, Set<Role> roles, MembershipStatus status) {
    }
}
