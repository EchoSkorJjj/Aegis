package com.usermgmt.identity.auth.account;

public enum TokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
    /** Admin-created account: single-use link to set the first password. */
    INVITE
}
