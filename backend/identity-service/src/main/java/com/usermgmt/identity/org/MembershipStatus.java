package com.usermgmt.identity.org;

/** Lifecycle of an {@link OrganizationMember} (ADR 0001). */
public enum MembershipStatus {

    /** Invited but not yet accepted — a brand-new identity that has not set a password. */
    INVITED,

    /** Active member of the organization. */
    ACTIVE,

    /** Temporarily suspended by an org admin; retained but cannot act in the org. */
    SUSPENDED
}
