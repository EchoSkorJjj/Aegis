package com.aegis.core.actions.pr.github;

import java.time.Clock;

import com.aegis.core.actions.pr.PrProvider;
import com.aegis.core.inventory.sbom.RepositoryReadAccess;
import com.aegis.core.platform.tenant.CurrentOrg;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * The GitHub App, when enabled: one installation-token source behind both the draft-pull-request provider
 * ({@code actions.upgrade}) and the inventory's read access for SBOM imports, each token narrowed to one
 * repository and one purpose.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aegis.actions.github", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GitHubProperties.class)
class GitHubAppConfig {

    private static final Logger log = LoggerFactory.getLogger(GitHubAppConfig.class);

    @Bean
    GitHubApi githubApi(GitHubProperties properties, ObjectMapper mapper, ObservationRegistry observations) {
        properties.validate();
        return new GitHubApi(properties.apiBaseUrl(), mapper, observations);
    }

    @Bean
    GitHubInstallationTokens githubInstallationTokens(GitHubProperties properties, GitHubApi api, CurrentOrg currentOrg) {
        GitHubAccounts accounts = GitHubAccounts.parse(properties.allowedAccounts());
        accounts.unbound().forEach(login -> log.warn("GitHub account '{}' is not bound to an organization, so every "
                + "organization in this deployment can use the GitHub App on it; bind it as {}=<organization id> "
                + "when more than one organization shares the deployment", login, login));
        Clock clock = Clock.systemUTC();
        var jwt = new GitHubAppJwt(properties.privateKeyPath(), properties.clientId(), clock);
        return new GitHubInstallationTokens(api, jwt, accounts, currentOrg::require, clock);
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
