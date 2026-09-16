package com.usermgmt.identity.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Memberships (ADR 0001). Used to resolve the orgs a user belongs to (the workspace switcher and the
 * default active org at login), to verify a switch-org request, and to pre-check invites.
 */
public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {

    /** Every org the user belongs to (drives the switcher and the login default). */
    List<OrganizationMember> findByUserId(UUID userId);

    /** The user's membership in one org, if any — used to verify switch-org and scope admin actions. */
    Optional<OrganizationMember> findByOrgIdAndUserId(UUID orgId, UUID userId);

    /** Whether the user already belongs to the org (invite pre-check). */
    boolean existsByOrgIdAndUserId(UUID orgId, UUID userId);
}
