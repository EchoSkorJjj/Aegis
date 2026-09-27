package com.aegis.core.inventory.sbom;

import com.aegis.core.inventory.sbom.RepositoryReadAccess.ReadGrant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The integration suite's SBOM generation stand-ins, never on the network: {@link FakeGitAndSyft} in place
 * of the real git and syft, and a GitHub-App-like integration that grants read access to github.com
 * repositories owned by {@code app-installed} and explains that it isn't installed anywhere else. Imported
 * once by {@code AbstractIntegrationTest}, so every integration test keeps sharing one context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SbomTestConfig {

    /** The installation token the fake integration grants. */
    public static final String APP_TOKEN = "fake-installation-token";

    @Bean
    @Primary
    public FakeGitAndSyft fakeGitAndSyft() {
        return new FakeGitAndSyft();
    }

    @Bean
    public RepositoryReadAccess fakeGitHubAppReadAccess() {
        return repository -> {
            if (!repository.host().equals("github.com")) {
                return ReadGrant.notCovered();
            }
            String owner = repository.path().split("/")[0];
            return owner.equals("app-installed")
                    ? ReadGrant.granted(new CloneCredential("x-access-token", APP_TOKEN), "the Aegis GitHub App")
                    : ReadGrant.unavailable("the Aegis GitHub App is not installed on " + owner);
        };
    }
}
