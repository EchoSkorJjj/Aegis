package com.usermgmt.identity.bootstrap;

import com.usermgmt.identity.support.AbstractIntegrationTest;
import com.usermgmt.identity.user.Role;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the opt-in admin bootstrap: with the properties set, a PLATFORM_ADMIN is seeded at startup.
 * (The other tests deliberately do NOT enable this path, proving it is off by default.)
 */
@SpringBootTest(properties = {
        "identity.bootstrap.admin-email=boot-admin@example.com",
        "identity.bootstrap.admin-password=password123",
        "identity.rate-limit.enabled=false"
})
class AdminBootstrapIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    UserRepository users;

    @Test
    void bootstrapSeedsAdminOnStartup() {
        User admin = users.findByEmail("boot-admin@example.com").orElseThrow();
        assertThat(admin.getRoles()).contains(Role.PLATFORM_ADMIN);
        assertThat(admin.isEmailVerified()).isTrue();
    }
}
