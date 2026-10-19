-- ADR 0004 Phase 3 (the contract step). Now that access tokens read org-scoped roles from
-- organization_members (Phase 2), drop the redundant org roles that Phase 1 copied into — and Phase 2
-- dual-wrote to — user_roles. From here on user_roles is PLATFORM-scoped only (the USER baseline and
-- PLATFORM_ADMIN); org roles (ORG_ADMIN / SERVICE_OWNER / ANALYST / COMPLIANCE_ADMIN) live solely on
-- the membership.
delete from user_roles
    where role in ('ORG_ADMIN', 'SERVICE_OWNER', 'ANALYST', 'COMPLIANCE_ADMIN');
