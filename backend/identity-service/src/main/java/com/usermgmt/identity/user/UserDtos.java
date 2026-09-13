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
            Instant createdAt) {

        public static UserResponse from(User u) {
            return new UserResponse(
                    u.getId(), u.getEmail(), u.getDisplayName(), u.getRoles(),
                    u.isEnabled(), u.isEmailVerified(), u.getCreatedAt());
        }
    }

    /** Partial update. Null fields are left unchanged; {@code enabled} is admin-only. */
    public record UpdateUserRequest(String displayName, Boolean enabled) {
    }

    public record UpdateRolesRequest(@NotEmpty Set<Role> roles) {
    }

    /** Admin-provisioned account: an email invite is sent for the user to set their password. */
    public record InviteUserRequest(@Email @NotBlank String email, String displayName) {
    }
}
