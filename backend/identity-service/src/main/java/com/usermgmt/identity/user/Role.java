package com.usermgmt.identity.user;

/**
 * Application roles. Granted per user, stored by name in {@code user_roles}, and carried in the JWT
 * {@code roles} claim (mapped to {@code ROLE_*} authorities). Because they are persisted by name,
 * new values may be added freely — but do not reorder or rename existing ones (that would orphan
 * rows in {@code user_roles}).
 *
 * <p>Two platform roles, plus the three Aegis "lines of defence" domain roles. The domain roles are
 * defined here as the single source of truth; aegis-core enforces them (owner-scoped
 * {@code @PreAuthorize}) as the domain APIs are built (see PROJECT_PLAN E4 story 3 onward).
 */
public enum Role {

    /** Any authenticated user — the baseline, granted on registration. */
    USER,

    /** Platform administrator: manages users and role assignments. Not an Aegis domain role. */
    ADMIN,

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
     * system administration. (Platform-level user and role management stays with {@link #ADMIN}.)
     */
    COMPLIANCE_ADMIN
}
