package com.usermgmt.identity.auth.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dev/default sender: logs the token instead of sending mail, so email verification and password
 * reset work without an SMTP server. In production, supply an SMTP-backed {@link EmailSender} bean
 * (and do not log tokens there).
 */
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void sendEmailVerification(String toEmail, String token) {
        log.info("[dev-mail] email verification for {} -> token={} (wire SMTP for production)", toEmail, token);
    }

    @Override
    public void sendPasswordReset(String toEmail, String token) {
        log.info("[dev-mail] password reset for {} -> token={} (wire SMTP for production)", toEmail, token);
    }

    @Override
    public void sendLoginOtp(String toEmail, String code) {
        log.info("[dev-mail] login OTP for {} -> code={} (wire SMTP for production)", toEmail, code);
    }

    @Override
    public void sendInvite(String toEmail, String token) {
        log.info("[dev-mail] invite for {} -> token={} (wire SMTP for production)", toEmail, token);
    }

    @Override
    public void sendOwnerNotification(String toEmail, OwnerNotification notification) {
        log.info("[dev-mail] owner notification for {} -> {} item(s) (wire SMTP for production)", toEmail,
                notification.items().size());
    }
}
