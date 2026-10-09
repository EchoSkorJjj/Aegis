package com.usermgmt.identity.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.usermgmt.identity.auth.email.EmailSender;
import com.usermgmt.identity.auth.email.OwnerNotification;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Test {@link EmailSender} (marked {@link Primary} so it replaces the logging one) that captures
 * the tokens, letting tests complete the verification / reset flows.
 */
@Component
@Primary
public class CapturingEmailSender implements EmailSender {

    private final Map<String, String> verificationTokens = new ConcurrentHashMap<>();
    private final Map<String, String> resetTokens = new ConcurrentHashMap<>();
    private final Map<String, String> loginOtps = new ConcurrentHashMap<>();
    private final Map<String, String> inviteTokens = new ConcurrentHashMap<>();
    private final Map<String, List<OwnerNotification>> ownerNotifications = new ConcurrentHashMap<>();

    @Override
    public void sendEmailVerification(String toEmail, String token) {
        verificationTokens.put(toEmail, token);
    }

    @Override
    public void sendPasswordReset(String toEmail, String token) {
        resetTokens.put(toEmail, token);
    }

    @Override
    public void sendLoginOtp(String toEmail, String code) {
        loginOtps.put(toEmail, code);
    }

    @Override
    public void sendInvite(String toEmail, String token) {
        inviteTokens.put(toEmail, token);
    }

    @Override
    public void sendOwnerNotification(String toEmail, OwnerNotification notification) {
        ownerNotifications.computeIfAbsent(toEmail, email -> new CopyOnWriteArrayList<>()).add(notification);
    }

    /** Every owner notification sent to this address, oldest first. */
    public List<OwnerNotification> ownerNotificationsFor(String email) {
        return ownerNotifications.getOrDefault(email, List.of());
    }

    public String inviteTokenFor(String email) {
        return inviteTokens.get(email);
    }

    public String verificationTokenFor(String email) {
        return verificationTokens.get(email);
    }

    public String resetTokenFor(String email) {
        return resetTokens.get(email);
    }

    public String loginOtpFor(String email) {
        return loginOtps.get(email);
    }
}
