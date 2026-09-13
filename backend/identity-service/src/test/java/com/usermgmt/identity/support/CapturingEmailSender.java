package com.usermgmt.identity.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.usermgmt.identity.auth.email.EmailSender;
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
