package com.aegis.core.inventory;

/**
 * Method-security expressions for the Aegis domain roles carried in identity-service JWTs.
 *
 * <p>The split follows the three lines of defence (ADR §6d): the <b>governance</b> roles — {@code ANALYST}
 * and {@code COMPLIANCE_ADMIN} — register services, set the attributes that drive a service's score
 * (criticality, exposure) and assign who is accountable for it; a <b>{@code SERVICE_OWNER}</b> keeps the
 * services they are assigned to accurate (their SBOM, components and vendors) but cannot re-grade their
 * own risk or appoint owners. Everyone with a domain role can read.
 */
public final class InventoryRoles {

    /** The role an organization member must hold to be assigned as a service's owner. */
    public static final String SERVICE_OWNER = "SERVICE_OWNER";

    public static final String READ = "hasAnyRole('SERVICE_OWNER','ANALYST','COMPLIANCE_ADMIN')";

    /** Governance: create/edit/delete inventory, set criticality/exposure, assign a service's owners. */
    public static final String WRITE = "hasAnyRole('ANALYST','COMPLIANCE_ADMIN')";

    /**
     * Maintaining what one service is made of — importing its SBOM and linking its components and
     * vendors. Governance roles may do it for any service; a {@code SERVICE_OWNER} only for a service
     * they are an assigned owner of (checked by {@link ServiceOwnership}, org-scoped). The annotated
     * method must take the service's id as a parameter named {@code serviceId}.
     */
    public static final String COMPOSE = "hasAnyRole('ANALYST','COMPLIANCE_ADMIN')"
            + " or (hasRole('SERVICE_OWNER') and @serviceOwnership.isAssignedOwner(#serviceId, authentication))";

    private InventoryRoles() {
    }
}
