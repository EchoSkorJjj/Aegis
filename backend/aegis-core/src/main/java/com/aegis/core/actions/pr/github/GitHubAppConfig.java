package com.aegis.core.actions.pr.github;

import java.time.Clock;

import com.aegis.core.actions.pr.PrProvider;
import com.aegis.core.inventory.sbom.RepositoryReadAccess;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * The GitHub App, when enabled: one installation-token source behind both the draft-pull-request provider
 * ({@code actions.upgrade}) and the inventory's read access for SBOM imports, each token narrowed to one
 * repository and one purpose — and usable only for installations the caller's organization has connected
 * ({@link GitHubConnections}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aegis.actions.github", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GitHubProperties.class)
class GitHubAppConfig {

    @Bean
    GitHubApi githubApi(GitHubProperties properties, ObjectMapper mapper, ObservationRegistry observations) {
        properties.validate();
        return new GitHubApi(properties.apiBaseUrl(), mapper, observations);
    }

    @Bean
    GitHubAppJwt githubAppJwt(GitHubProperties properties) {
        return new GitHubAppJwt(properties.privateKeyPath(), properties.clientId(), Clock.systemUTC());
    }

    @Bean
    GitHubInstallationTokens githubInstallationTokens(GitHubApi api, GitHubAppJwt jwt, GitHubConnectionStore connections) {
        return new GitHubInstallationTokens(api, jwt, connections::hasAny, connections::isConnected, Clock.systemUTC());
    }

    @Bean
    GitHubUserAuthorization githubUserAuthorization(GitHubProperties properties, GitHubApi api, ObjectMapper mapper,
            ObservationRegistry observations) {
        return new GitHubUserAuthorization(properties, api, mapper, observations);
    }

    @Bean
    GitHubConnections githubConnections(GitHubProperties properties, GitHubApi api, GitHubAppJwt jwt,
            GitHubUserAuthorization users, GitHubConnectionStore connections) {
        return new GitHubConnections(properties, api, jwt, users, connections, Clock.systemUTC());
    }

    @Bean
    PrProvider githubPrProvider(GitHubProperties properties, GitHubApi api, GitHubInstallationTokens tokens) {
        return new GitHubAppPrProvider(api, tokens, properties.hosts());
    }

    @Bean
    RepositoryReadAccess githubRepositoryReadAccess(GitHubProperties properties, GitHubInstallationTokens tokens) {
        return new GitHubRepositoryReadAccess(tokens, properties.hosts());
    }
}
