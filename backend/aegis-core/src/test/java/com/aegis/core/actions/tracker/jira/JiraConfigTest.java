package com.aegis.core.actions.tracker.jira;

import java.security.SecureRandom;
import java.util.Base64;

import com.aegis.core.actions.tracker.IssueTracker;
import com.aegis.core.platform.tenant.CurrentOrg;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Jira stays off until configured, and a Jira that can't work stops aegis-core at startup, saying why. */
class JiraConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JiraConfig.class)
            .withBean(CurrentOrg.class)
            .withBean(JiraConnectionStore.class, () -> Mockito.mock(JiraConnectionStore.class))
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(ObservationRegistry.class, () -> ObservationRegistry.NOOP);

    @Test
    void offByDefault() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(IssueTracker.class)
                .doesNotHaveBean(JiraConnections.class));
    }

    @Test
    void enabledFilesThroughJiraWithTheDefaultsAtlassianUses() {
        enabled().withPropertyValues("aegis.secrets.key=" + key()).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(IssueTracker.class).hasSingleBean(JiraConnections.class);
            JiraProperties properties = context.getBean(JiraProperties.class);
            assertThat(properties.authBaseUrl().toString()).isEqualTo("https://auth.atlassian.com");
            assertThat(properties.apiBaseUrl().toString()).isEqualTo("https://api.atlassian.com");
            assertThat(properties.callbackUrl().toString())
                    .isEqualTo("http://localhost:3000/admin/integrations/jira/callback");
            assertThat(properties.toString()).contains("jira-client").doesNotContain("jira-client-secret");
        });
    }

    @Test
    void enabledRequiresTheClientSecretAndTheSecretsKey() {
        runner.withPropertyValues("aegis.actions.jira.enabled=true", "aegis.actions.jira.client-id=jira-client")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("Jira client-secret is required when enabled"));
        enabled().run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("AEGIS_SECRETS_KEY is required to store integration credentials"));
        enabled().withPropertyValues("aegis.secrets.key=" + key(), "aegis.actions.jira.api-base-url=http://jira.example")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("Atlassian URLs must use HTTPS (HTTP loopback is allowed for tests)"));
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("aegis.actions.jira.enabled=true", "aegis.actions.jira.client-id=jira-client",
                "aegis.actions.jira.client-secret=jira-client-secret");
    }

    private static String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
