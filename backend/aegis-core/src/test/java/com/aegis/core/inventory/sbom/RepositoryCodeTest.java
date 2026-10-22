package com.aegis.core.inventory.sbom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ImportScan;
import com.aegis.core.inventory.domain.ImportScan.Site;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.domain.SbomImportSource;
import com.aegis.core.inventory.sbom.RepositoryCode.Excerpt;
import com.aegis.core.inventory.sbom.RepositoryCode.Kind;
import com.aegis.core.inventory.sbom.RepositoryCode.Opened;
import com.aegis.core.inventory.sbom.RepositoryCode.Read;
import com.aegis.core.inventory.sbom.RepositoryCode.Searched;
import com.aegis.core.inventory.sbom.RepositoryReadAccess.ReadGrant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A service's code, read back on demand at the commit its last import read (E11-5): one checkout of only the package
 * language's source files, through the import's hardened git and access (never a pasted token), deleted when done;
 * from it, the lines using what each import binds, and a search for names the advisory or its fix writes — or why the
 * code can't be read.
 */
class RepositoryCodeTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static final String MOVED_ON = "fedcba9876543210fedcba9876543210fedcba98";

    private final SbomGenerationProperties properties = new SbomGenerationProperties(
            true, List.of("github.com", "gitlab.com"), "git", "syft",
            Duration.ofSeconds(90), Duration.ofSeconds(180), 512L * 1024 * 1024, 64L * 1024 * 1024);
    private final FakeGit git = new FakeGit();

    @Test
    void theCodeIsCheckedOutOnceAtTheCommitTheImportReadWithOnlyThePackagesLanguage() {
        git.file("src/app.ts", "import { merge } from 'lodash';\nexport const c = merge(a, b);\n");

        try (RepositoryCode.Code code = open("npm").code()) {
            code.read(List.of(new Site("src/app.ts", 1)), List.of(), 6);
        }

        assertThat(git.ran("clone")).contains("--filter=blob:none", "--no-checkout", "--depth", "1");
        // JavaScript and TypeScript sources only, in any case; vendored and build folders left out.
        assertThat(git.ran("sparse-checkout")).contains("*.[tT][sS]", "*.[jJ][sS]", "!node_modules/")
                .doesNotContain("*.[pP][yY]", "/*");
        assertThat(git.ran("checkout")).contains(REVISION);
        assertThat(git.ran("fetch")).isEmpty();
        assertThat(git.roots).allSatisfy(root -> assertThat(root).doesNotExist()); // deleted when closed
    }

    @Test
    void theCommitTheImportReadIsFetchedWhenTheBranchHasMovedOn() {
        git.head = MOVED_ON;
        git.file("src/app.ts", "import x from 'lodash';\n");

        open("npm").code().close();

        assertThat(git.ran("fetch")).containsSubsequence("--depth", "1", "origin", REVISION);
    }

    @Test
    void whereTheCodeUsesWhatItImportsIsShownRatherThanTheImportsAroundIt() {
        git.file("src/render.ts", numbered(40, Map.of(1, "import { template } from 'lodash';",
                2, "import { join } from 'node:path';", 30, "  return template(req.body.view)(data);"))
                .replace("line ", "a longer line of code that is not about lodash at all, number "));

        Read read = read("npm", List.of(new Site("src/render.ts", 1)), List.of());

        assertThat(read.excerpts()).singleElement().satisfies(excerpt -> {
            assertThat(excerpt.kind()).isEqualTo(Kind.USE);
            assertThat(excerpt.line()).isEqualTo(30);
            assertThat(excerpt.startLine()).isEqualTo(27);
            assertThat(excerpt.text().lines()).hasSizeBetween(5, 7).contains("  return template(req.body.view)(data);");
            assertThat(excerpt.term()).isNull();
        });
        assertThat(read.importsFound()).isTrue();
    }

    @Test
    void aFileShorterThanAnExcerptIsSentWhole() {
        // A config file is short, and what it leaves out (no cacheComponents) is often the point.
        String config = "import type { NextConfig } from \"next\";\n\nconst nextConfig: NextConfig = {\n"
                + "  output: \"standalone\",\n  reactCompiler: true,\n};\n\nexport default nextConfig;\n";
        git.file("next.config.ts", config);

        Read read = read("npm", List.of(new Site("next.config.ts", 1)), List.of());

        assertThat(read.excerpts()).singleElement().satisfies(excerpt -> {
            assertThat(excerpt.startLine()).isEqualTo(1);
            assertThat(excerpt.text()).isEqualTo(config.strip());
            assertThat(excerpt.kind()).isEqualTo(Kind.IMPORT);
            assertThat(excerpt.line()).isEqualTo(1);
        });
    }

    @Test
    void aFileThatImportsButNeverUsesWhatItBindsShowsItsImport() {
        git.file("src/types.ts", "import type { Metadata } from 'next';\nexport const metadata: Metadata = {};\n");

        Read read = read("npm", List.of(new Site("src/types.ts", 1)), List.of());

        assertThat(read.excerpts()).extracting(Excerpt::kind, Excerpt::line).containsExactly(tuple(Kind.IMPORT, 1));
    }

    @Test
    void theSearchFindsWhatTheAdvisoryNamesAndFollowsAnImportOfItToItsUse() {
        git.file("src/main/java/acme/Mapper.java", """
                package acme;
                import com.fasterxml.jackson.databind.ObjectMapper;
                class Mapper {
                    Upload read(String json) throws Exception {
                        return new ObjectMapper().readValue(json, Upload.class);
                    }
                }
                """);
        git.file("src/main/java/acme/Upload.java", """
                package acme;

                import java.nio.file.Path;

                record Upload(String name, Path file) {
                }
                """);
        git.file("src/main/java/acme/Unrelated.java", "package acme;\nclass Unrelated {}\n");

        Read read = read("maven", List.of(new Site("src/main/java/acme/Mapper.java", 2)),
                List.of("java.nio.file.Path", "NioPathDeserializer"));

        assertThat(read.excerpts()).extracting(Excerpt::path, Excerpt::kind, Excerpt::line, Excerpt::term)
                .containsExactly(tuple("src/main/java/acme/Mapper.java", Kind.USE, 5, null),
                        tuple("src/main/java/acme/Upload.java", Kind.MATCH, 5, "java.nio.file.Path"));
        assertThat(read.searches()).containsExactly(new Searched("java.nio.file.Path", 3, 1, 1, true),
                new Searched("NioPathDeserializer", 3, 0, 0, true));
    }

    @Test
    void aQualifiedTypeIsAlsoFoundThroughItsPackagesWildcardImport() {
        // "No match" for java.nio.file.Path must rule out a Path field: a wildcard import declares it too.
        git.file("src/main/java/acme/Upload.java", "package acme;\nimport java.nio.file.*;\n\nrecord Upload(Path file) {}\n");
        git.file("src/main/java/acme/Other.java", "package acme;\nimport java.nio.file.attribute.*;\nclass Other {}\n");

        Read read = read("maven", List.of(), List.of("java.nio.file.Path"));

        assertThat(read.searches()).containsExactly(new Searched("java.nio.file.Path", 2, 1, 1, true));
        assertThat(read.excerpts()).extracting(Excerpt::path, Excerpt::kind, Excerpt::line)
                .containsExactly(tuple("src/main/java/acme/Upload.java", Kind.MATCH, 2));
    }

    @Test
    void aNameOfSeveralWordsIsFoundWhicheverWayTheCodeWritesThem() {
        // An advisory's "Cache Component" is the config key cacheComponents: "no match" must not miss it.
        git.file("next.config.ts", "export default { cacheComponents: true };\n");
        git.file("src/flags.ts", "export const CACHE_COMPONENT = 1;\n");
        git.file("src/other.ts", "export const cacheComponentsFolder = 'x';\n");

        Read read = read("npm", List.of(), List.of("CacheComponent"));

        assertThat(read.searches()).containsExactly(new Searched("CacheComponent", 3, 2, 2, true));
    }

    @Test
    void aTermThatIsNoPlainNameOrThatWouldLookForSecretsIsNeverSearched() {
        String padding = ("const filler = '" + "x".repeat(100) + "';\n").repeat(7); // longer than an excerpt
        git.file("src/app.js", "const password = process.env.DB;\n" + padding + "const _ = require('lodash');\n"
                + "_.template(x);\n");

        Read read = read("npm", List.of(new Site("src/app.js", 9)),
                List.of("password", "dbToken", "a b", "x", "template"));

        assertThat(read.searches()).extracting(Searched::term).containsExactly("template");
        assertThat(read.excerpts()).noneMatch(excerpt -> excerpt.text().contains("password"));
    }

    @Test
    void usesComeFirstThenEachTermsMatchesInFilesNotYetShownUpToTheCap() {
        for (int index = 1; index <= 5; index++) {
            git.file("src/use" + index + ".js", "const _ = require('lodash');\n\n\n\n\n\n\n\n_.merge(a, b);\n");
            git.file("src/tpl" + index + ".js", "\n\n\n\n\n\n\n\n\nrender.template(view);\n");
        }
        List<Site> sites = IntStream.rangeClosed(1, 5).mapToObj(index -> new Site("src/use" + index + ".js", 1)).toList();

        Read read = read("npm", sites, List.of("template"));

        assertThat(read.excerpts()).hasSize(6).extracting(Excerpt::kind)
                .containsExactly(Kind.USE, Kind.USE, Kind.USE, Kind.MATCH, Kind.MATCH, Kind.MATCH);
        assertThat(read.searches()).containsExactly(new Searched("template", 10, 5, 5, true));
    }

    @Test
    void vendoredBuiltSecretLookingAndOversizedFilesAreNeverRead() {
        git.file("node_modules/lodash/template.js", "template();\n");
        git.file("dist/app.min.js", "template();\n");
        git.file("src/credentials.js", "template();\n");
        git.file("src/huge.js", "template();\n" + "x".repeat((int) ImportScanner.MAX_FILE_BYTES));
        git.file("src/app.js", "const _ = require('lodash');\n");

        Read read = read("npm", List.of(new Site("src/app.js", 1)), List.of("template"));

        // A file too large to read leaves the search incomplete, so finding nothing proves less.
        assertThat(read.searches()).containsExactly(new Searched("template", 1, 0, 0, false));
    }

    @Test
    void linesGoneFromTheRepositoryAtThatCommitAreNoImportsFound() {
        Read read = read("npm", List.of(new Site("gone.ts", 3)), List.of());

        assertThat(read.excerpts()).isEmpty();
        assertThat(read.importsFound()).isFalse();
    }

    @Test
    void aRepositoryAegisCannotReadNowSaysWhyAndNoPastedTokenIsKeptToTryWith() {
        git.cloneFails = "fatal: could not read Username for 'https://github.com': terminal prompts disabled";

        Opened opened = code().open(scanned("https://github.com/acme/private"), "npm");

        assertThat(opened.code()).isNull();
        assertThat(opened.unreadable()).isEqualTo("Aegis can't read https://github.com/acme/private now: it may be"
                + " private, and an access token pasted for an import isn't kept — connect the GitHub App to read it");
        assertThat(git.envs.getFirst()).doesNotContainKey("GIT_ASKPASS");
    }

    @Test
    void aCommitTheHostNoLongerHasSaysSo() {
        git.head = MOVED_ON;
        git.fetchFails = true;

        assertThat(code().open(scanned("https://github.com/acme/web"), "npm").unreadable())
                .isEqualTo("The commit the import read (0123456) is no longer in https://github.com/acme/web;"
                        + " import the SBOM from it again");
    }

    @Test
    void theOrganizationsGitHubAppReadsAPrivateRepositoryWithItsTokenKeptOutOfTheCommandLine() {
        git.file("a.js", "import x from 'lodash';");
        RepositoryReadAccess app = repository -> ReadGrant.granted(
                new CloneCredential("x-access-token", "installation-token"), "the Aegis GitHub App");

        Opened opened = code(app).open(scanned("https://github.com/acme/private"), "npm");

        assertThat(opened.unreadable()).isNull();
        opened.code().close();
        assertThat(git.envs.getFirst()).containsEntry("AEGIS_GIT_TOKEN", "installation-token").containsKey("GIT_ASKPASS");
        assertThat(git.commands).noneMatch(command -> String.join(" ", command).contains("installation-token"));
    }

    @Test
    void whatCannotBeReadAtAllSaysWhy() {
        BusinessService unscanned = new BusinessService(UUID.randomUUID(), "Web", BusinessCriticality.HIGH);
        assertThat(code().open(unscanned, "npm").unreadable())
                .isEqualTo("The service's code hasn't been read: import the SBOM from its repository");
        assertThat(new RepositoryCode(properties, (RepoSbomGenerator) null, new RepositoryAccess(List.of()))
                .open(scanned("https://github.com/acme/web"), "npm").unreadable())
                .isEqualTo("Reading the service's code is switched off on this server");
        assertThat(code().open(scanned("https://github.com/acme/web"), "cargo").unreadable())
                .isEqualTo("Aegis doesn't read cargo code yet");
        assertThat(git.commands).isEmpty();
    }

    private Opened open(String ecosystem) {
        return code().open(scanned("https://github.com/acme/web"), ecosystem);
    }

    private Read read(String ecosystem, List<Site> sites, List<String> terms) {
        try (RepositoryCode.Code code = open(ecosystem).code()) {
            return code.read(sites, terms, 6);
        }
    }

    private RepositoryCode code(RepositoryReadAccess... integrations) {
        return new RepositoryCode(properties, new RepoSbomGenerator(properties, git),
                new RepositoryAccess(List.of(integrations)));
    }

    private BusinessService scanned(String repository) {
        BusinessService service = new BusinessService(UUID.randomUUID(), "Web", BusinessCriticality.HIGH);
        service.linkRepository(RepositoryUrl.parse(repository, properties.allowedHosts()), null);
        service.recordSbomImport(SbomImportSource.PUBLIC, Instant.now());
        service.recordImportScan(new ImportScan(REVISION, List.of("npm"), 3, null, List.of()), Instant.now());
        return service;
    }

    /** {@code count} lines, "line N", except those {@code at} gives. */
    private static String numbered(int count, Map<Integer, String> at) {
        return IntStream.rangeClosed(1, count).mapToObj(index -> at.getOrDefault(index, "line " + index))
                .collect(Collectors.joining("\n")) + "\n";
    }

    /** git, faked: a clone, the commit checked out (every file the repository holds), and what each step was given. */
    private static final class FakeGit implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        final List<Map<String, String>> envs = new ArrayList<>();
        final Map<String, String> files = new LinkedHashMap<>();
        final List<Path> roots = new ArrayList<>();
        String head = REVISION;
        String cloneFails;
        boolean fetchFails;

        void file(String path, String content) {
            files.put(path, content);
        }

        List<String> ran(String subcommand) {
            return commands.stream().filter(command -> command.contains(subcommand)).findFirst().orElse(List.of());
        }

        @Override
        public CommandResult run(List<String> command, Path workingDir, Map<String, String> env, Duration timeout)
                throws Exception {
            commands.add(List.copyOf(command));
            envs.add(new HashMap<>(env));
            if (command.contains("clone")) {
                if (cloneFails != null) {
                    return new CommandResult(128, "", "Cloning into 'repo'...\n" + cloneFails, false);
                }
                Path root = Path.of(command.getLast());
                Files.createDirectories(root);
                roots.add(root);
                return new CommandResult(0, "", "", false);
            }
            if (command.contains("rev-parse")) {
                return new CommandResult(0, head + "\n", "", false);
            }
            if (command.contains("fetch") && fetchFails) {
                return new CommandResult(128, "", "fatal: remote error: upload-pack: not our ref " + REVISION, false);
            }
            if (command.contains("checkout")) {
                Path root = Path.of(command.get(command.indexOf("-C") + 1));
                for (Map.Entry<String, String> file : files.entrySet()) {
                    Path target = root.resolve(file.getKey());
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, file.getValue());
                }
            }
            return new CommandResult(0, "", "", false);
        }
    }
}
