package com.usermgmt.identity.user;

/**
 * Application roles. Granted per user, stored by name in {@code user_roles}, and carried in the JWT
 * {@code roles} claim (mapped to {@code ROLE_*} authorities). Because they are persisted by name,
 * new values may be added freely — but do not reorder or rename existing ones (that would orphan
 * rows in {@code user_roles}).
 *
 * <p>The baseline USER, two admin tiers (PLATFORM_ADMIN / ORG_ADMIN), and the three Aegis domain roles. The domain roles are
 * defined here as the single source of truth; aegis-core enforces them (owner-scoped
 * {@code @PreAuthorize}) as the domain APIs are built (see PROJECT_PLAN E4 story 3 onward).
 */
public enum Role {

    /** Any authenticated user — the baseline, granted on registration. */
    USER,

    /**
     * 1st line — Service Owner: owns one or more business services; sees their services' findings
     * and approves / edits / overrides the response for services they own.
     */
    SERVICE_OWNER,

    /**
     * 2nd line — Security / Risk Analyst: triages the full findings queue across all services,
     * validates, and escalates.
     */
    ANALYST,

    /**
     * 3rd line — Compliance / risk oversight: the audit trail and exportable evidence, plus Aegis
     * system administration. (Platform-level user and role management stays with {@link #PLATFORM_ADMIN}.)
     */
    COMPLIANCE_ADMIN,

    /**
     * Platform operator (ADR 0001): org-less; provisions and manages organizations. Sits above
     * tenancy, distinct from an organization's own admin.
     */
    PLATFORM_ADMIN,

    /**
     * Organization administrator / owner (ADR 0001): manages users and role assignments within
     * their own organization. Granted to the creator on self-serve org signup.
     */
    ORG_ADMIN;

    /**
     * Whether this role belongs to the identity itself rather than to an org membership (ADR 0001).
     * Platform-scoped roles live in {@code user_roles} and travel in every access token; org-scoped
     * roles live on the {@code organization_members} membership and travel only in a token scoped to
     * that org.
     */
    public boolean isPlatformScoped() {
        return this == USER || this == PLATFORM_ADMIN;
    }
}
