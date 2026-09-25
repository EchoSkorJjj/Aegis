package com.aegis.core.actions.pr.github;

import java.time.Clock;

import com.aegis.core.actions.pr.PrProvider;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aegis.actions.github", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GitHubProperties.class)
class GitHubPrConfig {
    @Bean
    PrProvider githubPrProvider(GitHubProperties properties, ObjectMapper mapper, ObservationRegistry observations) {
        properties.validate();
        Clock clock = Clock.systemUTC();
        var jwt = new GitHubAppJwt(properties.privateKeyPath(), properties.clientId(), clock);
        var api = new GitHubApi(properties.apiBaseUrl(), mapper, observations);
        var tokens = new GitHubInstallationTokens(api, jwt, properties.allowedAccounts(), clock);
        return new GitHubAppPrProvider(api, tokens, properties.hosts());
    }
}
