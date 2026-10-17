package com.usermgmt.identity.auth.email;

/**
 * Delivers account emails. The default implementation logs the token (dev); provide an
 * SMTP-backed bean to actually send mail in production.
 */
public interface EmailSender {

    void sendEmailVerification(String toEmail, String token);

    void sendPasswordReset(String toEmail, String token);

    void sendLoginOtp(String toEmail, String code);

    /** Invites an admin-created user to set their first password. */
    void sendInvite(String toEmail, String token);

    /** Tells a service owner which items in Aegis need their decision (aegis-core's notifications). */
    void sendOwnerNotification(String toEmail, OwnerNotification notification);
}
