package com.aegis.core.actions.pr.github;

import java.nio.file.Files;
import java.nio.file.Path;

import com.aegis.core.actions.pr.PrProvider;
import com.aegis.core.actions.pr.PrProviders;
import com.aegis.core.actions.pr.RepositoryRef;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubPrConfigTest {
    @TempDir Path temporary;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GitHubPrConfig.class, PrProviders.class)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(ObservationRegistry.class, () -> ObservationRegistry.NOOP);

    @Test
    void disabledByDefaultWithoutAnyConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PrProvider.class).doesNotHaveBean(GitHubProperties.class);
            assertThat(context).hasSingleBean(PrProviders.class);
        });
    }

    @Test
    void explicitDisabledIgnoresMissingKey() {
        runner.withPropertyValues("aegis.actions.github.enabled=false",
                "aegis.actions.github.private-key-path=/missing/test-only.pem").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(PrProvider.class));
    }

    @Test
    void enabledBindsPropertiesAndExposesOnlyPortWithoutNetworkCalls() throws Exception {
        Path key = GitHubAppPrProviderTest.writeKey(temporary, GitHubAppPrProviderTest.generateKey());
        enabled().withPropertyValues("aegis.actions.github.private-key-path=" + key,
                "aegis.actions.github.api-base-url=https://github.example/api/v3",
                "aegis.actions.github.hosts=github.example",
                "aegis.actions.github.allowed-accounts=Sandbox,SECOND").run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PrProvider.class);
                    GitHubProperties props = context.getBean(GitHubProperties.class);
                    assertThat(props.enabled()).isTrue();
                    assertThat(props.clientId()).isEqualTo("test-client");
                    assertThat(props.privateKeyPath()).isEqualTo(key);
                    assertThat(props.apiBaseUrl().toString()).isEqualTo("https://github.example/api/v3");
                    assertThat(props.allowedAccounts()).containsExactlyInAnyOrder("sandbox", "second");
                    var repo = new RepositoryRef("github.example", "sandbox/repo");
                    assertThat(context.getBean(PrProviders.class).forRepository(repo).id()).isEqualTo("github");
                    assertThat(context).doesNotHaveBean(GitHubApi.class).doesNotHaveBean(GitHubInstallationTokens.class);
                });
    }

    @Test
    void enabledDefaultsToGithubAndEmptyAllowList() throws Exception {
        Path key = GitHubAppPrProviderTest.writeKey(temporary, GitHubAppPrProviderTest.generateKey());
        enabled().withPropertyValues("aegis.actions.github.private-key-path=" + key).run(context -> {
            assertThat(context).hasNotFailed();
            GitHubProperties props = context.getBean(GitHubProperties.class);
            assertThat(props.apiBaseUrl().toString()).isEqualTo("https://api.github.com");
            assertThat(props.hosts()).containsExactly("github.com");
            assertThat(props.allowedAccounts()).isEmpty();
        });
    }

    @Test
    void enabledRequiresClientId() {
        runner.withPropertyValues("aegis.actions.github.enabled=true").run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage("GitHub App client-id is required when enabled"));
    }

    @Test
    void enabledRequiresKeyPath() {
        enabled().run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("GitHub App private-key-path is required when enabled"));
    }

    @Test
    void enabledRejectsUnreadableKey() {
        enabled().withPropertyValues("aegis.actions.github.private-key-path=" + temporary.resolve("does-not-exist.pem"))
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("GitHub App key must be a readable, unencrypted PKCS#8 RSA PEM (at least 2048 bits)"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-----BEGIN RSA PRIVATE KEY-----\nsecret\n-----END RSA PRIVATE KEY-----",
            "-----BEGIN PRIVATE KEY-----\ninvalid-secret\n-----END PRIVATE KEY-----"})
    void invalidOrPkcs1KeysFailSafelyAtStartup(String pem) throws Exception {
        Path key = Files.writeString(temporary.resolve("invalid.pem"), pem);
        enabled().withPropertyValues("aegis.actions.github.private-key-path=" + key).run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("GitHub App key must be a readable, unencrypted PKCS#8 RSA PEM (at least 2048 bits)"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://github.example/api/v3", "https://user:password@github.example",
            "https://github.example?token=secret", "https://github.example#fragment"})
    void rejectsUnsafeApiConfiguration(String uri) {
        enabled().withPropertyValues("aegis.actions.github.api-base-url=" + uri).run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "GitHub API base URL must use HTTPS (HTTP loopback is allowed for tests)"));
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("aegis.actions.github.enabled=true", "aegis.actions.github.client-id=test-client");
    }
}
