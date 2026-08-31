package com.usermgmt.identity.auth;

public enum TokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
    /** Admin-created account: single-use link to set the first password. */
    INVITE
}
