package com.aegis.core.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The integration suite's repository host and AI drafter: in-memory, deterministic, never on the network.
 * Imported once by {@link AbstractIntegrationTest}, so every integration test keeps sharing one context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ActionsTestConfig {

    @Bean
    public FakeRepositoryHost fakeRepositoryHost() {
        return new FakeRepositoryHost();
    }

    @Bean
    @Primary
    public FakeAiDrafter fakeAiDrafter() {
        return new FakeAiDrafter();
    }

    @Bean
    public FakeIssueTracker fakeIssueTracker() {
        return new FakeIssueTracker();
    }
}
