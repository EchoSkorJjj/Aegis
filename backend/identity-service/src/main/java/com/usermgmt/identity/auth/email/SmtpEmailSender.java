package com.usermgmt.identity.auth.email;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Line breaks, tabs and other controls, Unicode's line and paragraph separators included. */
    private static final Pattern BREAKS = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]+");
    /** A URL: with a scheme, or an address starting with {@code www.}. Never needed in an item's text. */
    private static final Pattern URL = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://\\S*|\\bwww\\.\\S+");
    /**
     * A host followed by a path, which a mail client would make a link of too. Usually a package (Go's
     * {@code github.com/gin-gonic/gin}), so it is kept but defanged ({@code github[.]com/…}). A bare name with dots
     * stays as it is: package names look like one ({@code org.apache.logging.log4j:log4j-core}).
     */
    private static final Pattern HOST_PATH = Pattern.compile("(?i)\\b((?:[a-z0-9-]+\\.)+[a-z]{2,})(/\\S*)");
    static final String LINK_REMOVED = "[link removed]";

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

    @Override
    public void sendOwnerNotification(String toEmail, OwnerNotification notification) {
        List<OwnerNotification.Item> items = notification.items();
        String subject = items.size() == 1
                ? "Aegis: " + plain(items.get(0).serviceName()) + " needs your decision"
                : "Aegis: " + items.size() + " items need your decision";
        StringBuilder body = new StringBuilder(items.size() == 1
                ? "An item for a service you own is ready for your decision:\n\n"
                : "Items for services you own are ready for your decision:\n\n");
        for (OwnerNotification.Item item : items) {
            body.append("- ").append(plain(item.title())).append(" (").append(plain(item.serviceName()))
                    .append(")\n  ").append(frontendBaseUrl).append(item.path()).append("\n");
        }
        body.append("\nEverything waiting in your services: ").append(frontendBaseUrl).append("/findings/mine")
                .append("\n\nAegis emails you once about each item; they are under the bell in the app too.");
        send(toEmail, subject, body.toString());
    }

    /**
     * Feed and inventory text is untrusted: one line, so it can't add headers or fake new paragraphs, with no URL
     * and nothing a mail client would make a link of, so the only links in the email are the app's own — a vendor's
     * status page title can't put one in front of an owner as if Aegis had.
     */
    static String plain(String text) {
        if (text == null) {
            return "";
        }
        String line = URL.matcher(BREAKS.matcher(text).replaceAll(" ")).replaceAll(LINK_REMOVED);
        return HOST_PATH.matcher(line)
                .replaceAll(link -> Matcher.quoteReplacement(link.group(1).replace(".", "[.]") + link.group(2)))
                .strip();
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
