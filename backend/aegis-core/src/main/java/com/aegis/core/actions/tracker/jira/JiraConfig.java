package com.aegis.core.actions.tracker.jira;

import java.time.Clock;

import com.aegis.core.actions.tracker.IssueTracker;
import com.aegis.core.platform.crypto.SecretBox;
import com.aegis.core.platform.tenant.CurrentOrg;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Jira, when enabled: the organization's risk tickets are filed in the Jira site its admin connected. It
 * needs the Atlassian app's client ID and secret, and AEGIS_SECRETS_KEY to seal the tokens it keeps; a
 * missing one stops aegis-core at startup with what is wrong.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aegis.actions.jira", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(JiraProperties.class)
class JiraConfig {

    @Bean
    JiraApi jiraApi(JiraProperties properties, ObjectMapper mapper, ObservationRegistry observations) {
        properties.validate();
        return new JiraApi(properties, mapper, observations);
    }

    @Bean
    JiraConnections jiraConnections(JiraProperties properties, JiraApi api, JiraConnectionStore store,
            CurrentOrg currentOrg, @Value("${aegis.secrets.key:}") String secretsKey) {
        return new JiraConnections(properties, api, store, SecretBox.fromBase64(secretsKey), currentOrg::require,
                Clock.systemUTC());
    }

    @Bean
    IssueTracker jiraIssueTracker(JiraApi api, JiraConnections connections) {
        return new JiraIssueTracker(api, connections);
    }
}
