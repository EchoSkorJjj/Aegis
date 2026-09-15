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
