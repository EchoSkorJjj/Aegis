package com.aegis.core.actions.pr.github;

import java.nio.file.Files;
import java.nio.file.Path;

import com.aegis.core.actions.pr.PrProvider;
import com.aegis.core.actions.pr.PrProviders;
import com.aegis.core.actions.pr.RepositoryRef;
import com.aegis.core.inventory.sbom.RepositoryReadAccess;
import com.aegis.core.platform.tenant.CurrentOrg;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubAppConfigTest {
    @TempDir Path temporary;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GitHubAppConfig.class, PrProviders.class)
            .withBean(CurrentOrg.class)
            .withBean(GitHubConnectionStore.class, () -> Mockito.mock(GitHubConnectionStore.class))
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(ObservationRegistry.class, () -> ObservationRegistry.NOOP);

    @Test
    void disabledByDefaultWithoutAnyConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PrProvider.class).doesNotHaveBean(GitHubProperties.class)
                    .doesNotHaveBean(RepositoryReadAccess.class);
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
    void enabledBindsPropertiesAndExposesBothPortsWithoutNetworkCalls() throws Exception {
        Path key = GitHubAppPrProviderTest.writeKey(temporary, GitHubAppPrProviderTest.generateKey());
        enabled().withPropertyValues("aegis.actions.github.private-key-path=" + key,
                "aegis.actions.github.api-base-url=https://github.example/api/v3",
                "aegis.actions.github.web-base-url=https://github.example",
                "aegis.actions.github.callback-url=https://aegis.example/admin/integrations/github/callback",
                "aegis.actions.github.hosts=github.example").run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PrProvider.class)
                            .hasSingleBean(RepositoryReadAccess.class).hasSingleBean(GitHubConnections.class);
                    GitHubProperties props = context.getBean(GitHubProperties.class);
                    assertThat(props.enabled()).isTrue();
                    assertThat(props.clientId()).isEqualTo("test-client");
                    assertThat(props.clientSecret()).isEqualTo("test-client-secret");
                    assertThat(props.privateKeyPath()).isEqualTo(key);
                    assertThat(props.apiBaseUrl().toString()).isEqualTo("https://github.example/api/v3");
                    assertThat(props.webBaseUrl().toString()).isEqualTo("https://github.example");
                    assertThat(props.callbackUrl().toString())
                            .isEqualTo("https://aegis.example/admin/integrations/github/callback");
                    var repo = new RepositoryRef("github.example", "sandbox/repo");
                    assertThat(context.getBean(PrProviders.class).forRepository(repo).id()).isEqualTo("github");
                });
    }

    @Test
    void enabledDefaultsToGithubAndTheLocalFrontend() throws Exception {
        Path key = GitHubAppPrProviderTest.writeKey(temporary, GitHubAppPrProviderTest.generateKey());
        enabled().withPropertyValues("aegis.actions.github.private-key-path=" + key).run(context -> {
            assertThat(context).hasNotFailed();
            GitHubProperties props = context.getBean(GitHubProperties.class);
            assertThat(props.apiBaseUrl().toString()).isEqualTo("https://api.github.com");
            assertThat(props.webBaseUrl().toString()).isEqualTo("https://github.com");
            assertThat(props.callbackUrl().toString())
                    .isEqualTo("http://localhost:3000/admin/integrations/github/callback");
            assertThat(props.hosts()).containsExactly("github.com");
        });
    }

    @Test
    void enabledRequiresClientSecret() {
        runner.withPropertyValues("aegis.actions.github.enabled=true", "aegis.actions.github.client-id=test-client")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("GitHub App client-secret is required when enabled"));
    }

    @Test
    void theClientSecretIsNeverPrinted() throws Exception {
        Path key = GitHubAppPrProviderTest.writeKey(temporary, GitHubAppPrProviderTest.generateKey());
        enabled().withPropertyValues("aegis.actions.github.private-key-path=" + key).run(context ->
                assertThat(context.getBean(GitHubProperties.class).toString())
                        .contains("test-client").doesNotContain("test-client-secret"));
    }

    @Test
    void enabledRequiresClientId() {
        runner.withPropertyValues("aegis.actions.github.enabled=true").run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage("GitHub App client-id is required when enabled"));
    }

    @Test
    void enabledRequiresAKey() {
        enabled().run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("GitHub App private-key or private-key-path is required when enabled"));
    }

    @Test
    void enabledAcceptsTheKeyTextItself() throws Exception {
        String pem = Files.readString(GitHubAppPrProviderTest.writeKey(temporary, GitHubAppPrProviderTest.generateKey()));
        enabled().withPropertyValues("aegis.actions.github.private-key=" + pem).run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(PrProvider.class));
    }

    @Test
    void theKeyTextMayCarryItsLineBreaksEscaped() throws Exception {
        String pem = Files.readString(GitHubAppPrProviderTest.writeKey(temporary, GitHubAppPrProviderTest.generateKey()));
        String oneLine = pem.strip().replace("\n", "\\n");
        enabled().withPropertyValues("aegis.actions.github.private-key=" + oneLine).run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(PrProvider.class));
    }

    @Test
    void theKeyTextWinsOverThePath() throws Exception {
        String pem = Files.readString(GitHubAppPrProviderTest.writeKey(temporary, GitHubAppPrProviderTest.generateKey()));
        enabled().withPropertyValues("aegis.actions.github.private-key=" + pem,
                "aegis.actions.github.private-key-path=" + temporary.resolve("does-not-exist.pem")).run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(PrProvider.class));
    }

    @Test
    void theKeyIsNeverPrinted() throws Exception {
        String pem = Files.readString(GitHubAppPrProviderTest.writeKey(temporary, GitHubAppPrProviderTest.generateKey()));
        String body = pem.lines().filter(line -> !line.startsWith("-----")).findFirst().orElseThrow();
        enabled().withPropertyValues("aegis.actions.github.private-key=" + pem).run(context ->
                assertThat(context.getBean(GitHubProperties.class).toString()).doesNotContain(body));
    }

    @Test
    void invalidKeyTextFailsSafelyAtStartup() {
        enabled().withPropertyValues(
                "aegis.actions.github.private-key=-----BEGIN RSA PRIVATE KEY-----\nsecret\n-----END RSA PRIVATE KEY-----")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("GitHub App key must be a readable, unencrypted PKCS#8 RSA PEM (at least 2048 bits)"));
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
        return runner.withPropertyValues("aegis.actions.github.enabled=true", "aegis.actions.github.client-id=test-client",
                "aegis.actions.github.client-secret=test-client-secret");
    }
}
