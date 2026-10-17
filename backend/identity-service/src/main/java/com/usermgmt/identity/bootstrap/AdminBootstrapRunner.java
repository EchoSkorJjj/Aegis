package com.usermgmt.identity.bootstrap;

import java.util.UUID;

import com.usermgmt.identity.user.Role;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds/promotes an initial PLATFORM_ADMIN from configuration on startup — the standard pattern for a
 * self-hosted identity service (cf. Keycloak {@code KEYCLOAK_ADMIN}, Grafana
 * {@code GF_SECURITY_ADMIN_*}, and Postgres' own {@code POSTGRES_USER}).
 *
 * <p>Safety by design:
 * <ul>
 *   <li><b>Opt-in</b> — the bean only exists when {@code identity.bootstrap.admin-email} is set,
 *       so there is never a default admin.</li>
 *   <li><b>Idempotent</b> — creates the user if missing, grants PLATFORM_ADMIN if not already held,
 *       otherwise does nothing. Safe on every restart.</li>
 *   <li>The password is used <b>only</b> when creating a new user, is read from env/secret,
 *       and is never logged.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "identity.bootstrap", name = "admin-email")
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminBootstrapRunner(UserRepository users,
                                PasswordEncoder passwordEncoder,
                                @Value("${identity.bootstrap.admin-email:}") String adminEmail,
                                @Value("${identity.bootstrap.admin-password:}") String adminPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = adminEmail.trim().toLowerCase();
        if (email.isEmpty()) {
            return; // defensive: the @ConditionalOnProperty should prevent this
        }

        User admin = users.findByEmail(email).orElse(null);
        if (admin == null) {
            if (adminPassword.isBlank()) {
                log.error("identity.bootstrap.admin-email is set but admin-password is missing; "
                        + "no admin account was created for {}", email);
                return;
            }
            admin = new User(UUID.randomUUID(), email, passwordEncoder.encode(adminPassword), "Administrator");
            admin.setEmailVerified(true);
            admin.getRoles().add(Role.USER);
            admin.getRoles().add(Role.PLATFORM_ADMIN);
            users.save(admin);
            log.info("Bootstrap: created PLATFORM_ADMIN account {}", email);
            return;
        }

        if (admin.getRoles().add(Role.PLATFORM_ADMIN)) {
            users.save(admin);
            log.info("Bootstrap: granted PLATFORM_ADMIN to existing user {}", email);
        } else {
            log.debug("Bootstrap: {} already has PLATFORM_ADMIN; nothing to do", email);
        }
    }
}
