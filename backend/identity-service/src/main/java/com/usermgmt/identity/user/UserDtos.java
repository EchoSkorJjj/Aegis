package com.usermgmt.identity.user;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public final class UserDtos {

    private UserDtos() {
    }

    public record UserResponse(
            UUID id,
            String email,
            String displayName,
            Set<Role> roles,
            boolean enabled,
            boolean emailVerified,
            UUID orgId,
            Instant createdAt) {

        public static UserResponse from(User u) {
            return new UserResponse(
                    u.getId(), u.getEmail(), u.getDisplayName(), u.getRoles(),
                    u.isEnabled(), u.isEmailVerified(), u.getLastActiveOrgId(), u.getCreatedAt());
        }

        /**
         * With explicit effective roles + the viewed org (ADR 0001): used by the org-scoped admin views
         * where {@code roles} are the user's roles in that org (platform roles ∪ the membership's roles)
         * rather than the raw {@code user_roles}.
         */
        public static UserResponse of(User u, Set<Role> roles, UUID orgId) {
            return new UserResponse(
                    u.getId(), u.getEmail(), u.getDisplayName(), roles,
                    u.isEnabled(), u.isEmailVerified(), orgId, u.getCreatedAt());
        }
    }

    /** Partial update. Null fields are left unchanged; {@code enabled} is admin-only. */
    public record UpdateUserRequest(String displayName, Boolean enabled) {
    }

    public record UpdateRolesRequest(@NotEmpty Set<Role> roles) {
    }

    /**
     * Admin-provisioned account: an email invite is sent for the user to set their password. The
     * invited user joins the inviter's organization; {@code roles} are granted on top of {@code USER}
     * (org admins may not grant platform-level roles).
     */
    public record InviteUserRequest(@Email @NotBlank String email, String displayName, Set<Role> roles) {
    }
}
