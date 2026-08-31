package com.usermgmt.identity.auth;

import com.usermgmt.identity.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * Tracks failed login attempts and applies temporary account lockout.
 *
 * <p>Each method runs in its own transaction so that a recorded failure is committed even though
 * {@code AuthService.login} then throws — otherwise the increment would roll back and lockout
 * could never trigger.
 */
@Service
public class LoginAttemptService {

    private final UserRepository users;
    private final int maxAttempts;
    private final Duration lockoutDuration;

    public LoginAttemptService(UserRepository users,
                               @Value("${identity.security.max-failed-logins:5}") int maxAttempts,
                               @Value("${identity.security.lockout-minutes:15}") long lockoutMinutes) {
        this.users = users;
        this.maxAttempts = maxAttempts;
        this.lockoutDuration = Duration.ofMinutes(lockoutMinutes);
    }

    @Transactional
    public void recordFailure(UUID userId) {
        users.findById(userId).ifPresent(user -> {
            user.registerFailedLogin(maxAttempts, lockoutDuration);
            users.save(user);
        });
    }

    @Transactional
    public void recordSuccess(UUID userId) {
        users.findById(userId).ifPresent(user -> {
            if (user.getFailedLoginAttempts() != 0 || user.getLockedUntil() != null) {
                user.clearLoginFailures();
                users.save(user);
            }
        });
    }
}
