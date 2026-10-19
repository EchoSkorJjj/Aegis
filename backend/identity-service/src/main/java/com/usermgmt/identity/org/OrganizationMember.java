package com.usermgmt.identity.org;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import com.usermgmt.identity.user.Role;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A user's membership in an organization (ADR 0001) — the join that lets one global identity belong
 * to many orgs, each with its own roles. A user joins a given org at most once
 * ({@code unique (org_id, user_id)}).
 *
 * <p>Scope of roles: <b>org-scoped</b> roles ({@code ORG_ADMIN} / {@code SERVICE_OWNER} /
 * {@code ANALYST} / {@code COMPLIANCE_ADMIN}) live here; <b>platform</b> roles ({@code PLATFORM_ADMIN}
 * and the {@code USER} baseline) stay on {@link com.usermgmt.identity.user.User}. An access token is
 * scoped to one active org and carries the platform roles plus that org's membership roles.
 */
@Entity
@Table(name = "organization_members")
public class OrganizationMember {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private MembershipStatus status = MembershipStatus.ACTIVE;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "organization_member_roles", joinColumns = @JoinColumn(name = "member_id"))
    @Column(name = "role", length = 40)
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected OrganizationMember() {
    }

    public OrganizationMember(UUID id, UUID orgId, UUID userId) {
        this.id = id;
        this.orgId = orgId;
        this.userId = userId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getUserId() {
        return userId;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public void setStatus(MembershipStatus status) {
        this.status = status;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
