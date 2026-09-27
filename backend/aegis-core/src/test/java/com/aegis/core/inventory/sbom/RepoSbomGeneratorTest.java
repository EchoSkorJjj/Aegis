package com.aegis.core.inventory.sbom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.sbom.CommandRunner.CommandResult;
import com.aegis.core.platform.common.ApiExceptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoSbomGeneratorTest {

    private static final String VALID_SBOM =
            "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":["
                    + "{\"type\":\"library\",\"name\":\"axios\",\"version\":\"1.2.3\",\"purl\":\"pkg:npm/axios@1.2.3\"}]}";

    private final SbomGenerationProperties properties = new SbomGenerationProperties(
            true, List.of("github.com", "gitlab.com", "bitbucket.org"), "git", "syft",
            Duration.ofSeconds(90), Duration.ofSeconds(180), 512L * 1024 * 1024, 64L * 1024 * 1024);

    private RepoSbomGenerator generator(FakeCommandRunner runner) {
        return new RepoSbomGenerator(properties, runner);
    }

    /** Generate as the service does: a validated URL, and a pasted token (host convention) or no credential. */
    private CycloneDxBom generate(FakeCommandRunner runner, String url, String ref, String token) {
        RepositoryUrl repo = RepositoryUrl.parse(url, properties.allowedHosts());
        CloneAccess access = token == null ? CloneAccess.anonymous(null)
                : CloneAccess.token(CloneCredential.forToken(repo.host(), token, null));
        return generator(runner).generate(repo, ref, access);
    }

    @Test
    void reconstructsACleanCloneUrlAndImportsSyftsCycloneDxOutput() {
        FakeCommandRunner runner = new FakeCommandRunner();

        CycloneDxBom bom = generate(runner, "https://github.com/acme/widgets", null, null);

        assertThat(bom.bomFormat()).isEqualTo("CycloneDX");
        assertThat(bom.components()).singleElement().satisfies(c -> assertThat(c.name()).isEqualTo("axios"));
        List<String> git = runner.commandStartingWith("git");
        assertThat(git).contains("clone", "--depth", "1", "--single-branch", "--no-tags")
                .contains("https://github.com/acme/widgets.git")
                .contains("protocol.file.allow=never", "protocol.ext.allow=never");
        List<String> syft = runner.commandStartingWith("syft");
        assertThat(syft).contains("scan").anyMatch(a -> a.startsWith("dir:")).anyMatch(a -> a.startsWith("cyclonedx-json="));
        // A public clone carries no credential.
        assertThat(runner.envStartingWith("git")).doesNotContainKey("GIT_ASKPASS");
        assertThat(runner.lastWorkingDir).doesNotExist();
    }

    @Test
    void handlesGitLabNestedGroups() {
        FakeCommandRunner runner = new FakeCommandRunner();

        generate(runner, "https://gitlab.com/group/subgroup/widgets.git", null, null);

        assertThat(runner.commandStartingWith("git")).contains("https://gitlab.com/group/subgroup/widgets.git");
    }

    @Test
    void authenticatesAPrivateCloneViaAskpassWithTheTokenKeptOutOfArgvAndUrl() {
        FakeCommandRunner runner = new FakeCommandRunner();
        String token = "ghp_secret_TOKEN_123";

        generate(runner, "https://github.com/acme/private", null, token);

        Map<String, String> env = runner.envStartingWith("git");
        // The token is handed to git via GIT_ASKPASS, from the environment.
        assertThat(env.get("GIT_ASKPASS")).endsWith("askpass.sh");
        assertThat(env).containsEntry("AEGIS_GIT_TOKEN", token);
        // Only a non-secret username is in the URL; the token is nowhere in the argument list.
        List<String> git = runner.commandStartingWith("git");
        assertThat(git).contains("https://x-access-token@github.com/acme/private.git");
        assertThat(git).noneMatch(arg -> arg.contains(token));
    }

    @Test
    void usesEachHostsConventionalAuthUsername() {
        FakeCommandRunner gh = new FakeCommandRunner();
        generate(gh, "https://github.com/acme/private", null, "t");
        assertThat(gh.commandStartingWith("git")).contains("https://x-access-token@github.com/acme/private.git");

        FakeCommandRunner gl = new FakeCommandRunner();
        generate(gl, "https://gitlab.com/group/sub/private", null, "t");
        assertThat(gl.commandStartingWith("git")).contains("https://oauth2@gitlab.com/group/sub/private.git");

        FakeCommandRunner bb = new FakeCommandRunner();
        generate(bb, "https://bitbucket.org/team/private", null, "ATCTT3xFfGN0-access-token");
        assertThat(bb.commandStartingWith("git")).contains("https://x-token-auth@bitbucket.org/team/private.git");

        // Bitbucket API tokens (which replaced app passwords) take a different fixed username.
        FakeCommandRunner api = new FakeCommandRunner();
        generate(api, "https://bitbucket.org/team/private", null, "ATATT3xFfGF0-api-token");
        assertThat(api.commandStartingWith("git"))
                .contains("https://x-bitbucket-api-token-auth@bitbucket.org/team/private.git");
    }

    @Test
    void aGivenUsernameReplacesTheHostsConvention() {
        FakeCommandRunner runner = new FakeCommandRunner();
        RepositoryUrl repo = RepositoryUrl.parse("https://gitlab.com/group/private", properties.allowedHosts());

        generator(runner).generate(repo, null, CloneAccess.token(CloneCredential.forToken(repo.host(), "t0k", "jdoe")));

        assertThat(runner.commandStartingWith("git")).contains("https://jdoe@gitlab.com/group/private.git");
        assertThatThrownBy(() -> CloneCredential.forToken("gitlab.com", "t0k", "j@doe"))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> CloneCredential.forToken("gitlab.com", "two words", null))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThat(CloneCredential.forToken("github.com", "ghp_9f3cXq", null).toString()).doesNotContain("ghp_9f3cXq");
    }

    @Test
    void scrubsTheTokenFromAFailureMessage() {
        FakeCommandRunner runner = new FakeCommandRunner();
        String token = "ghp_leaky_TOKEN";
        runner.gitResult = new CommandResult(128, "", "fatal: bad credentials ghp_leaky_TOKEN", false);

        assertThatThrownBy(() -> generate(runner, "https://github.com/acme/private", null, token))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("***")
                .extracting(Throwable::getMessage, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .doesNotContain(token);
    }

    @Test
    void passesAValidatedBranchButRejectsAFlagLikeRef() {
        FakeCommandRunner runner = new FakeCommandRunner();

        generate(runner, "https://github.com/acme/widgets", "release/2.0", null);
        assertThat(runner.commandStartingWith("git")).containsSequence("--branch", "release/2.0");

        FakeCommandRunner clean = new FakeCommandRunner();
        assertThatThrownBy(() -> generate(clean, "https://github.com/acme/widgets", "--upload-pack=evil", null))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThat(clean.commands).isEmpty();
    }

    @Test
    void refusesAnyHostNotOnTheAllowList() {
        FakeCommandRunner runner = new FakeCommandRunner();
        assertThatThrownBy(() -> generate(runner, "https://evil.example/acme/widgets", null, null))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("host");
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void clonesAnSshCloneUrlOverHttps() {
        FakeCommandRunner runner = new FakeCommandRunner();

        generate(runner, "git@github.com:acme/widgets.git", null, null);

        assertThat(runner.commandStartingWith("git")).contains("https://github.com/acme/widgets.git")
                .noneMatch(arg -> arg.startsWith("git@") || arg.startsWith("ssh://"));
    }

    @Test
    void refusesNonHttpsAndMalformedUrls() {
        FakeCommandRunner runner = new FakeCommandRunner();
        assertThatThrownBy(() -> generate(runner, "http://github.com/acme/widgets", null, null))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> generate(runner, "https://github.com/only-owner", null, null))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void surfacesACloneFailureAsABadRequestAndStillCleansUp() {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.gitResult = new CommandResult(128, "", "fatal: unexpected disconnect while reading sideband packet", false);

        assertThatThrownBy(() -> generate(runner, "https://github.com/acme/broken", null, null))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessage("Could not clone the repository: fatal: unexpected disconnect while reading sideband packet");
        assertThat(runner.lastWorkingDir).doesNotExist();
    }

    @Test
    void givesUpOnAStalledTransferInsteadOfWaitingOutTheTimeout() {
        FakeCommandRunner runner = new FakeCommandRunner();

        generate(runner, "https://github.com/acme/widgets", null, null);

        assertThat(runner.commandStartingWith("git"))
                .containsSequence("-c", "http.lowSpeedLimit=1000", "-c", "http.lowSpeedTime=30");
    }

    @Test
    void aRepositoryThatNeedsCredentialsSaysWhatToDo() {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.gitResult = new CommandResult(128, "",
                "Cloning into 'repo'...\nfatal: could not read Username for 'https://github.com': terminal prompts disabled",
                false);
        RepositoryUrl repo = RepositoryUrl.parse("https://github.com/acme/private", properties.allowedHosts());

        assertThatThrownBy(() -> generator(runner).generate(repo, null, CloneAccess.anonymous(null)))
                .hasMessage("https://github.com/acme/private is private or doesn't exist. "
                        + "Add a read-only access token to import it");
        assertThatThrownBy(() -> generator(runner).generate(repo, null,
                CloneAccess.anonymous("the Aegis GitHub App is not installed on acme or can't reach this repository")))
                .hasMessageContaining("(the Aegis GitHub App is not installed on acme or can't reach this repository)");
    }

    @Test
    void aRefusedCredentialIsBlamedOnWhereItCameFrom() {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.gitResult = new CommandResult(128, "", "remote: Invalid username or token.\n"
                + "fatal: Authentication failed for 'https://github.com/acme/private.git/'", false);

        assertThatThrownBy(() -> generate(runner, "https://github.com/acme/private", null, "ghp_wrong"))
                .hasMessageStartingWith("The access token was refused for https://github.com/acme/private");
        RepositoryUrl repo = RepositoryUrl.parse("https://github.com/acme/private", properties.allowedHosts());
        CloneAccess app = CloneAccess.integration(new CloneCredential("x-access-token", "ghs_app"), "the Aegis GitHub App");
        assertThatThrownBy(() -> generator(runner).generate(repo, null, app))
                .hasMessageStartingWith("https://github.com/acme/private could not be read with the Aegis GitHub App");
    }

    @Test
    void anIntegrationsCredentialReachesGitOnlyThroughAskpass() {
        FakeCommandRunner runner = new FakeCommandRunner();
        RepositoryUrl repo = RepositoryUrl.parse("https://github.com/acme/private", properties.allowedHosts());

        generator(runner).generate(repo, null,
                CloneAccess.integration(new CloneCredential("x-access-token", "ghs_installation"), "the Aegis GitHub App"));

        assertThat(runner.envStartingWith("git")).containsEntry("AEGIS_GIT_TOKEN", "ghs_installation");
        assertThat(runner.commandStartingWith("git")).contains("https://x-access-token@github.com/acme/private.git")
                .noneMatch(arg -> arg.contains("ghs_installation"));
    }

    @Test
    void namesAMissingBranchAnUnreachableHostARateLimitAndATimeout() {
        FakeCommandRunner branch = new FakeCommandRunner();
        branch.gitResult = new CommandResult(128, "", "fatal: Remote branch develop not found in upstream origin", false);
        assertThatThrownBy(() -> generate(branch, "https://github.com/acme/widgets", "develop", null))
                .hasMessage("The branch or tag 'develop' doesn't exist in https://github.com/acme/widgets");

        FakeCommandRunner dns = new FakeCommandRunner();
        dns.gitResult = new CommandResult(128, "",
                "fatal: unable to access 'https://gitlab.com/g/r.git/': Could not resolve host: gitlab.com", false);
        assertThatThrownBy(() -> generate(dns, "https://gitlab.com/g/r", null, null))
                .hasMessage("Could not reach gitlab.com; check the URL, or try again");

        FakeCommandRunner limited = new FakeCommandRunner();
        limited.gitResult = new CommandResult(128, "",
                "fatal: unable to access 'https://github.com/acme/widgets.git/': The requested URL returned error: 429",
                false);
        assertThatThrownBy(() -> generate(limited, "https://github.com/acme/widgets", null, null))
                .hasMessageContaining("limiting requests without credentials; add an access token");

        FakeCommandRunner slow = new FakeCommandRunner();
        slow.gitResult = new CommandResult(-1, "", "", true);
        assertThatThrownBy(() -> generate(slow, "https://github.com/acme/widgets", null, null))
                .hasMessageContaining("timed out after 90 seconds");
    }

    @Test
    void fetchesOnlyWhatSyftCanReadTheTreeFirstThenEveryFileButMediaInOneBatch() {
        FakeCommandRunner runner = new FakeCommandRunner();

        generate(runner, "https://github.com/acme/widgets", null, "ghp_token");

        List<List<String>> git = runner.commandsStartingWith("git");
        assertThat(git).hasSize(3);
        assertThat(git.get(0)).contains("clone", "--filter=blob:none", "--no-checkout");
        String checkout = git.get(0).getLast();
        assertThat(git.get(1)).containsSequence("-C", checkout, "sparse-checkout", "set", "--no-cone", "/*")
                .contains("!*.[pP][nN][gG]", "!*.[jJ][pP][gG]", "!*.[mM][pP]4", "!*.[wW][oO][fF][fF]2", "!*.[pP][dD][fF]")
                .doesNotContain("!*.[jJ][aA][rR]", "!*.[zZ][iI][pP]");
        assertThat(git.get(2)).endsWith("-C", checkout, "checkout");
        // Every step is hardened alike, and the checkout fetches contents with the clone's credential.
        for (List<String> step : git) {
            assertThat(step).containsSequence("-c", "protocol.file.allow=never", "-c", "protocol.ext.allow=never",
                    "-c", "core.symlinks=false", "-c", "http.lowSpeedLimit=1000", "-c", "http.lowSpeedTime=30");
        }
        assertThat(runner.envs.get(2)).containsEntry("AEGIS_GIT_TOKEN", "ghp_token")
                .containsEntry("GIT_TERMINAL_PROMPT", "0");
        assertThat(runner.lastWorkingDir).doesNotExist();
    }

    @Test
    void aCheckoutThatFailsIsReportedAsTheCloneWouldBeAndTheTimeoutCoversEveryStep() {
        FakeCommandRunner refused = new FakeCommandRunner();
        refused.checkoutResult = new CommandResult(128, "", "remote: Invalid username or token.\n"
                + "fatal: Authentication failed for 'https://github.com/acme/private.git/'", false);
        RepositoryUrl repo = RepositoryUrl.parse("https://github.com/acme/private", properties.allowedHosts());
        assertThatThrownBy(() -> generator(refused).generate(repo, null,
                CloneAccess.integration(new CloneCredential("x-access-token", "ghs_app"), "the Aegis GitHub App")))
                .hasMessageStartingWith("https://github.com/acme/private could not be read with the Aegis GitHub App");
        assertThat(refused.lastWorkingDir).doesNotExist();

        FakeCommandRunner slow = new FakeCommandRunner();
        slow.checkoutResult = new CommandResult(-1, "", "", true);
        assertThatThrownBy(() -> generate(slow, "https://github.com/acme/widgets", null, null))
                .hasMessage("Cloning https://github.com/acme/widgets timed out after 90 seconds; github.com may be "
                        + "slow or unreachable, so try again");
        assertThat(slow.commands).noneMatch(command -> command.get(0).equals("syft"));
    }

    @Test
    void aPatternMatchesAnExtensionInAnyCase() {
        assertThat(RepoSbomGenerator.anyCase("png")).isEqualTo("[pP][nN][gG]");
        assertThat(RepoSbomGenerator.anyCase("mp4")).isEqualTo("[mM][pP]4");
        assertThat(RepoSbomGenerator.SPARSE_PATTERNS).first().isEqualTo("/*");
        assertThat(RepoSbomGenerator.SPARSE_PATTERNS).hasSize(RepoSbomGenerator.NOT_SCANNED.size() + 1);
    }

    @Test
    void surfacesAMissingSyftBinaryAsAClearBadRequest() {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.throwOn = "syft";

        assertThatThrownBy(() -> generate(runner, "https://github.com/acme/widgets", null, null))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("syft");
    }

    /** Fake runner: records commands + their env, fabricates git's checkout and syft's SBOM, no processes. */
    private static final class FakeCommandRunner implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        final List<Map<String, String>> envs = new ArrayList<>();
        /** The clone's result; {@link #checkoutResult} is the sparse checkout's and the checkout's. */
        CommandResult gitResult = new CommandResult(0, "", "", false);
        CommandResult checkoutResult = new CommandResult(0, "", "", false);
        CommandResult syftResult = new CommandResult(0, "", "", false);
        Path lastWorkingDir;
        String throwOn;

        @Override
        public CommandResult run(List<String> command, Path workingDir, Map<String, String> env, Duration timeout)
                throws Exception {
            commands.add(command);
            envs.add(new HashMap<>(env));
            lastWorkingDir = workingDir;
            String tool = command.get(0);
            if (tool.equals(throwOn)) {
                throw new java.io.IOException("Cannot run program \"" + tool + "\": No such file or directory");
            }
            if (tool.equals("git")) {
                if (!command.contains("clone")) {
                    return checkoutResult;
                }
                if (gitResult.succeeded()) {
                    Path checkout = Path.of(command.get(command.size() - 1));
                    Files.createDirectories(checkout);
                    Files.writeString(checkout.resolve("package.json"), "{}");
                }
                return gitResult;
            }
            if (syftResult.succeeded()) {
                String target = command.stream()
                        .filter(a -> a.startsWith("cyclonedx-json="))
                        .findFirst().orElseThrow()
                        .substring("cyclonedx-json=".length());
                Files.writeString(Path.of(target), VALID_SBOM);
            }
            return syftResult;
        }

        int indexOf(String tool) {
            for (int i = 0; i < commands.size(); i++) {
                if (commands.get(i).get(0).equals(tool)) {
                    return i;
                }
            }
            throw new AssertionError("no command started with " + tool);
        }

        List<String> commandStartingWith(String tool) {
            return commands.get(indexOf(tool));
        }

        List<List<String>> commandsStartingWith(String tool) {
            return commands.stream().filter(command -> command.get(0).equals(tool)).toList();
        }

        Map<String, String> envStartingWith(String tool) {
            return envs.get(indexOf(tool));
        }
    }
}
