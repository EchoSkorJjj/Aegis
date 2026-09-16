package com.aegis.core.tenant;

import java.util.Optional;
import java.util.UUID;

import com.aegis.core.common.ApiExceptions;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The organization (tenant) the current operation acts within — the aegis-core side of the
 * multi-tenancy model (ADR §5/§6). It is resolved from the access token's {@code org} claim, which
 * identity-service sets to the caller's active organization and only ever from a server-verified
 * membership. aegis-core never trusts a client-supplied org id.
 *
 * <p>Every org-scoped inventory read is confined to this org and every write is stamped with it, so a
 * caller with no org (a platform admin, who is org-less) cannot reach org-scoped domain data — and in
 * practice is already blocked by the domain-role {@code @PreAuthorize} on those endpoints.
 *
 * <p>Background work that runs without a request — the demo-estate seed — establishes an org with
 * {@link #runAs(UUID, Runnable)} rather than a token.
 */
@Component
public class CurrentOrg {

    private static final ThreadLocal<UUID> OVERRIDE = new ThreadLocal<>();

    /** The active org, or empty when the caller carries no {@code org} claim and no override is set. */
    public Optional<UUID> orgId() {
        UUID override = OVERRIDE.get();
        if (override != null) {
            return Optional.of(override);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            String org = token.getToken().getClaimAsString("org");
            if (org != null && !org.isBlank()) {
                return Optional.of(UUID.fromString(org));
            }
        }
        return Optional.empty();
    }

    /**
     * The active org, or a 403 — for org-scoped domain operations, which an org-less caller may not
     * perform.
     */
    public UUID require() {
        return orgId().orElseThrow(() -> new ApiExceptions.Forbidden("No organization context"));
    }

    /**
     * Run {@code task} as though the caller's active org were {@code orgId}. For background work with
     * no request (the demo seed); the override is confined to the current thread and unwound after.
     */
    public void runAs(UUID orgId, Runnable task) {
        UUID previous = OVERRIDE.get();
        OVERRIDE.set(orgId);
        try {
            task.run();
        } finally {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        }
    }
}
