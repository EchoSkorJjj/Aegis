package com.usermgmt.identity.audit;

import static com.usermgmt.identity.audit.AuditOutcome.FAILURE;
import static com.usermgmt.identity.audit.AuditOutcome.SUCCESS;

/**
 * The catalogue of security-relevant events written to the audit trail. Each type carries a
 * default {@link AuditOutcome} so call sites usually only name the event; the default can be
 * overridden (e.g. a normally-successful action that was rejected).
 */
public enum AuditEventType {

    // Account lifecycle
    USER_REGISTERED(SUCCESS),

    // Authentication
    LOGIN_SUCCEEDED(SUCCESS),
    LOGIN_FAILED(FAILURE),
    LOGIN_LOCKED_OUT(FAILURE),
    MFA_CHALLENGE_ISSUED(SUCCESS),
    MFA_SUCCEEDED(SUCCESS),
    MFA_FAILED(FAILURE),
    TOKEN_REFRESHED(SUCCESS),
    LOGOUT(SUCCESS),
    LOGOUT_ALL(SUCCESS),

    // Email verification & password reset
    EMAIL_VERIFICATION_SENT(SUCCESS),
    EMAIL_VERIFIED(SUCCESS),
    PASSWORD_RESET_REQUESTED(SUCCESS),
    PASSWORD_RESET_COMPLETED(SUCCESS),
    PASSWORD_CHANGED(SUCCESS),

    // Multi-factor enrollment
    MFA_ENROLLED(SUCCESS),
    MFA_DISABLED(SUCCESS),
    MFA_RECOVERY_CODES_GENERATED(SUCCESS),
    MFA_RECOVERY_CODE_USED(SUCCESS),

    // Administrative user management
    USER_INVITED(SUCCESS),
    USER_UPDATED(SUCCESS),
    USER_ROLES_CHANGED(SUCCESS),
    USER_DELETED(SUCCESS);

    private final AuditOutcome defaultOutcome;

    AuditEventType(AuditOutcome defaultOutcome) {
        this.defaultOutcome = defaultOutcome;
    }

    public AuditOutcome defaultOutcome() {
        return defaultOutcome;
    }
}
