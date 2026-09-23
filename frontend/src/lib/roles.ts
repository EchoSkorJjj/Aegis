import type { Role } from "@/lib/types";

/**
 * The org-scoped domain roles an org admin can assign to members (ADR 0001). Excludes the USER
 * baseline (always granted), ORG_ADMIN (managed separately), and platform roles.
 */
export const DOMAIN_ROLES: { value: Role; label: string }[] = [
  { value: "SERVICE_OWNER", label: "Service Owner" },
  { value: "ANALYST", label: "Analyst" },
  { value: "COMPLIANCE_ADMIN", label: "Compliance Admin" },
];

/**
 * Aegis domain-data access, mirroring aegis-core's `InventoryRoles` (the source of truth). READ covers
 * the estate map + change signals; WRITE (create/edit/delete inventory) excludes the read-only
 * `SERVICE_OWNER`. Used to gate the domain nav and controls; the backend still enforces both.
 */
const INVENTORY_READ_ROLES: Role[] = ["SERVICE_OWNER", "ANALYST", "COMPLIANCE_ADMIN"];
const INVENTORY_WRITE_ROLES: Role[] = ["ANALYST", "COMPLIANCE_ADMIN"];

export function canReadInventory(roles: readonly Role[]): boolean {
  return roles.some((role) => INVENTORY_READ_ROLES.includes(role));
}

export function canWriteInventory(roles: readonly Role[]): boolean {
  return roles.some((role) => INVENTORY_WRITE_ROLES.includes(role));
}

/**
 * May the caller maintain one service's composition (SBOM import, components, vendors)? Mirrors
 * aegis-core's `InventoryRoles.COMPOSE`: the governance roles for any service, or a SERVICE_OWNER for
 * a service they are an assigned owner of. Registering a service, changing its criticality/exposure,
 * and assigning its owners stay governance-only (`canWriteInventory`).
 */
export function canComposeService(roles: readonly Role[], isAssignedOwner: boolean): boolean {
  return canWriteInventory(roles) || (roles.includes("SERVICE_OWNER") && isAssignedOwner);
}
