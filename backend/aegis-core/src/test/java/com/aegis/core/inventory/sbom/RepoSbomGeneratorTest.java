package com.aegis.core.inventory.sbom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.aegis.core.common.ApiExceptions;
import com.aegis.core.inventory.sbom.CommandRunner.CommandResult;
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

    @Test
    void reconstructsACleanCloneUrlAndImportsSyftsCycloneDxOutput() {
        FakeCommandRunner runner = new FakeCommandRunner();

        CycloneDxBom bom = generator(runner).generate("https://github.com/acme/widgets", null, null);

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

        generator(runner).generate("https://gitlab.com/group/subgroup/widgets.git", null, null);

        assertThat(runner.commandStartingWith("git")).contains("https://gitlab.com/group/subgroup/widgets.git");
    }

    @Test
    void authenticatesAPrivateCloneViaAskpassWithTheTokenKeptOutOfArgvAndUrl() {
        FakeCommandRunner runner = new FakeCommandRunner();
        String token = "ghp_secret_TOKEN_123";

        generator(runner).generate("https://github.com/acme/private", null, token);

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
        generator(gh).generate("https://github.com/acme/private", null, "t");
        assertThat(gh.commandStartingWith("git")).contains("https://x-access-token@github.com/acme/private.git");

        FakeCommandRunner gl = new FakeCommandRunner();
        generator(gl).generate("https://gitlab.com/group/sub/private", null, "t");
        assertThat(gl.commandStartingWith("git")).contains("https://oauth2@gitlab.com/group/sub/private.git");

        FakeCommandRunner bb = new FakeCommandRunner();
        generator(bb).generate("https://bitbucket.org/team/private", null, "t");
        assertThat(bb.commandStartingWith("git")).contains("https://x-token-auth@bitbucket.org/team/private.git");
    }

    @Test
    void scrubsTheTokenFromAFailureMessage() {
        FakeCommandRunner runner = new FakeCommandRunner();
        String token = "ghp_leaky_TOKEN";
        runner.gitResult = new CommandResult(128, "", "fatal: bad credentials ghp_leaky_TOKEN", false);

        assertThatThrownBy(() -> generator(runner).generate("https://github.com/acme/private", null, token))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("***")
                .extracting(Throwable::getMessage, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .doesNotContain(token);
    }

    @Test
    void passesAValidatedBranchButRejectsAFlagLikeRef() {
        FakeCommandRunner runner = new FakeCommandRunner();

        generator(runner).generate("https://github.com/acme/widgets", "release/2.0", null);
        assertThat(runner.commandStartingWith("git")).containsSequence("--branch", "release/2.0");

        FakeCommandRunner clean = new FakeCommandRunner();
        assertThatThrownBy(() -> generator(clean).generate("https://github.com/acme/widgets", "--upload-pack=evil", null))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThat(clean.commands).isEmpty();
    }

    @Test
    void refusesAnyHostNotOnTheAllowList() {
        FakeCommandRunner runner = new FakeCommandRunner();
        assertThatThrownBy(() -> generator(runner).generate("https://evil.example/acme/widgets", null, null))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("host");
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void refusesNonHttpsAndMalformedUrls() {
        FakeCommandRunner runner = new FakeCommandRunner();
        assertThatThrownBy(() -> generator(runner).generate("git@github.com:acme/widgets.git", null, null))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> generator(runner).generate("http://github.com/acme/widgets", null, null))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> generator(runner).generate("https://github.com/only-owner", null, null))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void surfacesACloneFailureAsABadRequestAndStillCleansUp() {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.gitResult = new CommandResult(128, "", "fatal: repository not found", false);

        assertThatThrownBy(() -> generator(runner).generate("https://github.com/acme/missing", null, null))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("repository not found");
        assertThat(runner.lastWorkingDir).doesNotExist();
    }

    @Test
    void surfacesAMissingSyftBinaryAsAClearBadRequest() {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.throwOn = "syft";

        assertThatThrownBy(() -> generator(runner).generate("https://github.com/acme/widgets", null, null))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("syft");
    }

    /** Fake runner: records commands + their env, fabricates git's checkout and syft's SBOM, no processes. */
    private static final class FakeCommandRunner implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        final List<Map<String, String>> envs = new ArrayList<>();
        CommandResult gitResult = new CommandResult(0, "", "", false);
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

        Map<String, String> envStartingWith(String tool) {
            return envs.get(indexOf(tool));
        }
    }
}
