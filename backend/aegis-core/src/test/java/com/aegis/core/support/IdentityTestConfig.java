package com.aegis.core.support;

import com.aegis.core.inventory.InventoryService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the HTTP identity directory with {@link FakeIdentityDirectory} for the integration suite.
 * Imported once by {@link AbstractIntegrationTest}, so every integration test keeps sharing a single
 * cached application context rather than forking one per test class.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IdentityTestConfig {

    @Bean
    @Primary
    public FakeIdentityDirectory fakeIdentityDirectory() {
        return new FakeIdentityDirectory();
    }

    @Bean
    public TestOwners testOwners(FakeIdentityDirectory directory, InventoryService inventory) {
        return new TestOwners(directory, inventory);
    }
}
