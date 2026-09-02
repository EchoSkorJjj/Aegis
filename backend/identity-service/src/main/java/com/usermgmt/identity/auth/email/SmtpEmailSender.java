package com.usermgmt.identity.auth.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Real SMTP email sender, active only when {@code spring.mail.host} is configured (e.g. Mailhog in
 * dev, a provider in prod). Takes precedence over {@link LoggingEmailSender} via {@link Primary};
 * when no SMTP host is set the logging stub is used instead, so local runs and tests need no server.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;
    private final String frontendBaseUrl;

    public SmtpEmailSender(
            JavaMailSender mailSender,
            @Value("${identity.mail.from:no-reply@identity.local}") String from,
            @Value("${identity.frontend.base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.mailSender = mailSender;
        this.from = from;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public void sendEmailVerification(String toEmail, String token) {
        String link = frontendBaseUrl + "/verify-email?token=" + token;
        send(toEmail, "Verify your email",
                "Confirm your email address by opening this link:\n\n" + link
                        + "\n\nIf you didn't create an account, you can ignore this message.");
    }

    @Override
    public void sendPasswordReset(String toEmail, String token) {
        String link = frontendBaseUrl + "/reset-password?token=" + token;
        send(toEmail, "Reset your password",
                "We received a request to reset your password. Open this link to choose a new one:\n\n"
                        + link + "\n\nThe link expires soon. If you didn't request this, ignore this message.");
    }

    @Override
    public void sendInvite(String toEmail, String token) {
        String link = frontendBaseUrl + "/accept-invite?token=" + token;
        send(toEmail, "You've been invited",
                "An account has been created for you. Set your password to get started:\n\n"
                        + link + "\n\nThe link expires soon.");
    }

    @Override
    public void sendLoginOtp(String toEmail, String code) {
        send(toEmail, "Your login code",
                "Your one-time login code is:\n\n    " + code
                        + "\n\nIt expires shortly. If you didn't try to sign in, you can ignore this message.");
    }

    private void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
