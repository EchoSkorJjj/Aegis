package com.aegis.core.platform.identity;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A member of the caller's organization as identity-service reports it ({@code GET /users/{id}}).
 * {@code roles} are the member's <em>effective</em> roles in that org — platform roles plus the
 * membership's org roles — so an org-scoped role such as {@code SERVICE_OWNER} shows up here. Mirrors
 * the fields of identity-service's user response that aegis-core needs; the rest are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DirectoryUser(UUID id, String email, String displayName, Set<String> roles, boolean enabled) {

    public DirectoryUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** How to name this person to a reader: their display name, else their email, else their id. */
    public String label() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        return String.valueOf(id);
    }
}
