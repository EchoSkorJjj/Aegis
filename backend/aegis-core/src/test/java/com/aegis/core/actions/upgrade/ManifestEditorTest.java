package com.aegis.core.actions.upgrade;

import java.util.List;

import com.aegis.core.platform.common.ApiExceptions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestEditorTest {

    @Test
    void anUpgradeEditsTheRecordedManifestsItCanTheRootOnlyWhenNoneWereRecordedAndNeverGuesses() {
        ManifestEditor maven = new MavenPomEditor();
        // Nothing recorded (an uploaded SBOM, a hand-linked component): the root manifest, as before.
        assertThat(maven.manifestsFor(List.of())).containsExactly("pom.xml");
        assertThat(maven.manifestsFor(List.of("svc/pom.xml", " svc/pom.xml ", "gradle.lockfile", "../pom.xml")))
                .containsExactly("svc/pom.xml");
        // Recorded only in files Aegis doesn't edit: none, so no pull request is planned against a guess.
        assertThat(maven.manifestsFor(List.of("app/gradle.lockfile", "lib/vendored.jar"))).isEmpty();
        assertThat(new PipRequirementsEditor().manifestsFor(List.of("poetry.lock", "requirements-dev.txt"))).isEmpty();
        assertThat(new NpmPackageJsonEditor().manifestsFor(List.of("client/package.json", "server/package.json")))
                .containsExactly("client/package.json", "server/package.json");
    }

    private static final String LOG4J = "org.apache.logging.log4j:log4j-core";

    @Test
    void picksTheEditorForTheComponentsEcosystem() {
        assertThat(ManifestEditor.forEcosystem("maven")).get().isInstanceOf(MavenPomEditor.class);
        assertThat(ManifestEditor.forEcosystem("npm")).get().isInstanceOf(NpmPackageJsonEditor.class);
        assertThat(ManifestEditor.forEcosystem("PyPI")).get().isInstanceOf(PipRequirementsEditor.class);
        assertThat(ManifestEditor.forEcosystem("cargo")).isEmpty();
        assertThat(ManifestEditor.forEcosystem(null)).isEmpty();
    }

    @Nested
    class Maven {

        private final MavenPomEditor editor = new MavenPomEditor();

        @Test
        void changesOnlyTheDependencysVersion() {
            String pom = """
                    <project>
                      <dependencies>
                        <dependency>
                          <groupId>org.springframework</groupId>
                          <artifactId>spring-webmvc</artifactId>
                          <version>5.3.13</version>
                          <exclusions>
                            <exclusion>
                              <groupId>org.apache.logging.log4j</groupId>
                              <artifactId>log4j-core</artifactId>
                            </exclusion>
                          </exclusions>
                        </dependency>
                        <dependency>
                          <groupId>org.apache.logging.log4j</groupId>
                          <artifactId>log4j-core</artifactId>
                          <version> 2.14.1 </version>
                        </dependency>
                      </dependencies>
                    </project>
                    """;

            String upgraded = editor.upgrade(pom, LOG4J, "2.14.1", "2.17.1");

            assertThat(upgraded).isEqualTo(pom.replace("<version> 2.14.1 </version>", "<version> 2.17.1 </version>"));
        }

        @Test
        void updatesThePropertyTheVersionReadsFrom() {
            String pom = """
                    <project>
                      <properties><log4j.version>2.14.1</log4j.version></properties>
                      <dependencies>
                        <dependency>
                          <artifactId>log4j-core</artifactId>
                          <groupId>org.apache.logging.log4j</groupId>
                          <version>${log4j.version}</version>
                        </dependency>
                      </dependencies>
                    </project>
                    """;

            assertThat(editor.upgrade(pom, LOG4J, "2.14.1", "2.17.1"))
                    .isEqualTo(pom.replace("<log4j.version>2.14.1<", "<log4j.version>2.17.1<"));
        }

        @Test
        void refusesWhatThePomDoesNotControl() {
            String excludedOnly = """
                    <project><dependencies><dependency>
                      <groupId>org.springframework</groupId><artifactId>spring-webmvc</artifactId><version>5.3.13</version>
                      <exclusions><exclusion><groupId>org.apache.logging.log4j</groupId><artifactId>log4j-core</artifactId></exclusion></exclusions>
                    </dependency></dependencies></project>
                    """;
            assertThatThrownBy(() -> editor.upgrade(excludedOnly, LOG4J, "2.14.1", "2.17.1"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("not declared in pom.xml");

            String managed = "<dependency><groupId>org.apache.logging.log4j</groupId><artifactId>log4j-core</artifactId></dependency>";
            String elsewhere = managed.replace("</artifactId>", "</artifactId><version>2.13.0</version>");
            assertThatThrownBy(() -> editor.upgrade(elsewhere, LOG4J, "2.14.1", "2.17.1"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("at 2.13.0, not 2.14.1");

            // A property the pom sets to another version, or one Maven sets itself, isn't Aegis's to move.
            String otherValue = "<project><properties><log4j.version>2.13.0</log4j.version></properties><dependencies>"
                    + managed.replace("</artifactId>", "</artifactId><version>${log4j.version}</version>")
                    + "</dependencies></project>";
            assertThatThrownBy(() -> editor.upgrade(otherValue, LOG4J, "2.14.1", "2.17.1"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("does not set");
            String builtIn = managed.replace("</artifactId>", "</artifactId><version>${project.version}</version>");
            assertThatThrownBy(() -> editor.upgrade(builtIn, LOG4J, "2.14.1", "2.17.1"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("does not set");
            // Versionless, but not a pom Aegis can place a managed version in.
            assertThatThrownBy(() -> editor.upgrade(managed, LOG4J, "2.14.1", "2.17.1"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("no <project> element");

            assertThatThrownBy(() -> editor.upgrade(managed, "log4j-core", "2.14.1", "2.17.1"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("groupId:artifactId");
        }

        @Test
        void pinsADependencyAParentManagesInThePomsOwnDependencyManagement() {
            String pom = """
                    <project>
                      <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>2.6.1</version>
                      </parent>
                      <dependencies>
                        <dependency>
                          <groupId>org.apache.logging.log4j</groupId>
                          <artifactId>log4j-core</artifactId>
                        </dependency>
                      </dependencies>
                    </project>
                    """;

            assertThat(editor.upgrade(pom, LOG4J, "2.14.1", "2.17.1")).isEqualTo(pom.replace("  <dependencies>\n", """
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>org.apache.logging.log4j</groupId>
                            <artifactId>log4j-core</artifactId>
                            <version>2.17.1</version>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                      <dependencies>
                    """));
        }

        @Test
        void setsAVersionPropertyOnlyAParentDefinesInThePom() {
            String pom = """
                    <project>
                        <properties>
                            <java.version>17</java.version>
                        </properties>
                        <dependencies>
                            <dependency>
                                <groupId>org.apache.logging.log4j</groupId>
                                <artifactId>log4j-core</artifactId>
                                <version>${log4j2.version}</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """;
            assertThat(editor.upgrade(pom, LOG4J, "2.14.1", "2.17.1")).isEqualTo(pom.replace("    </properties>",
                    "        <log4j2.version>2.17.1</log4j2.version>\n    </properties>"));

            String withoutProperties = pom.replace("    <properties>\n        <java.version>17</java.version>\n"
                    + "    </properties>\n", "");
            assertThat(editor.upgrade(withoutProperties, LOG4J, "2.14.1", "2.17.1")).isEqualTo(withoutProperties.replace(
                    "    <dependencies>", "    <properties>\n        <log4j2.version>2.17.1</log4j2.version>\n"
                            + "    </properties>\n    <dependencies>"));
        }

        @Test
        void overridesADependencyThePomDoesNotDeclareInItsDependencyManagement() {
            String pom = """
                    <project>
                        <dependencyManagement>
                            <dependencies>
                                <dependency>
                                    <groupId>com.fasterxml.jackson</groupId>
                                    <artifactId>jackson-bom</artifactId>
                                    <version>2.15.0</version>
                                    <type>pom</type>
                                    <scope>import</scope>
                                </dependency>
                            </dependencies>
                        </dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework</groupId>
                                <artifactId>spring-webmvc</artifactId>
                                <version>5.3.13</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """;
            String managedLog4j = """
                                <dependency>
                                    <groupId>org.apache.logging.log4j</groupId>
                                    <artifactId>log4j-core</artifactId>
                                    <version>2.17.1</version>
                                </dependency>
                    """;
            String overridden = editor.override(pom, LOG4J, "2.14.1", "2.17.1", "org.springframework:spring-webmvc");
            assertThat(overridden).isEqualTo(pom.replace("        </dependencies>\n    </dependencyManagement>",
                    managedLog4j + "        </dependencies>\n    </dependencyManagement>"));

            // Managed already at an old version: that version moves; at the fixed one, there's nothing to do.
            assertThat(editor.override(overridden.replace("<version>2.17.1</version>", "<version>2.14.1</version>"),
                    LOG4J, "2.14.1", "2.17.1", null)).isEqualTo(overridden);
            assertThatThrownBy(() -> editor.override(overridden, LOG4J, "2.14.1", "2.17.1", null))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("already manages");

            // No dependencyManagement yet: one is added before the dependencies, at the pom's own indentation.
            String plain = "<project>\n  <dependencies>\n    <dependency>\n      <groupId>a</groupId>\n"
                    + "      <artifactId>b</artifactId>\n      <version>1</version>\n    </dependency>\n  </dependencies>\n"
                    + "</project>\n";
            assertThat(editor.override(plain, LOG4J, "2.14.1", "2.17.1", "a:b")).isEqualTo(plain.replace(
                    "  <dependencies>", "  <dependencyManagement>\n    <dependencies>\n      <dependency>\n"
                            + "        <groupId>org.apache.logging.log4j</groupId>\n"
                            + "        <artifactId>log4j-core</artifactId>\n        <version>2.17.1</version>\n"
                            + "      </dependency>\n    </dependencies>\n  </dependencyManagement>\n  <dependencies>"));

            // Declared after all: its declaration is bumped instead.
            String declared = plain.replace("<groupId>a</groupId>", "<groupId>org.apache.logging.log4j</groupId>")
                    .replace("<artifactId>b</artifactId>", "<artifactId>log4j-core</artifactId>")
                    .replace("<version>1</version>", "<version>2.14.1</version>");
            assertThat(editor.override(declared, LOG4J, "2.14.1", "2.17.1", null))
                    .isEqualTo(declared.replace("2.14.1", "2.17.1"));
        }
    }

    @Nested
    class Npm {

        private final NpmPackageJsonEditor editor = new NpmPackageJsonEditor();

        @Test
        void raisesTheVersionKeepingTheRangeOperatorAndTheLayout() {
            String manifest = """
                    {
                      "name": "customer-portal",
                      "dependencies": {
                        "axios": "^0.21.1",
                        "@babel/traverse":   "~7.23.0"
                      },
                      "devDependencies": { "axios": "0.21.1" }
                    }
                    """;

            assertThat(editor.upgrade(manifest, "axios", "0.21.1", "0.21.2"))
                    .isEqualTo(manifest.replace("\"^0.21.1\"", "\"^0.21.2\"").replace("\"0.21.1\"", "\"0.21.2\""));
            assertThat(editor.upgrade(manifest, "@babel/traverse", "7.23.0", "7.23.2"))
                    .contains("\"@babel/traverse\":   \"~7.23.2\"");
        }

        @Test
        void theLockfileBesideEachEditedManifestIsLeftToTheReviewerByName() {
            assertThat(editor.followUps(List.of("package.json"))).singleElement().asString()
                    .startsWith("Refresh the lockfile in the repository root and commit it to this branch: run `npm install`")
                    .contains("`npm ci` refuses to install");
            assertThat(editor.followUps(List.of("web/app`x/package.json", "server/package.json", "client/package.json",
                    "package.json")))
                    .singleElement().asString()
                    .startsWith("Refresh the lockfiles in the repository root, `client/`, `server/` and `web/app'x/` "
                            + "and commit them");
            assertThat(new MavenPomEditor().followUps(List.of("pom.xml"))).isEmpty();
        }

        @Test
        void overridesAPackageTheProjectDoesNotDeclareKeepingTheLayout() {
            String manifest = """
                    {
                      "name": "client",
                      "dependencies": {
                        "shadcn": "^4.0.5"
                      }
                    }
                    """;
            String overridden = editor.override(manifest, "hono", "4.12.7", "4.13.7", "shadcn");
            assertThat(overridden).isEqualTo("""
                    {
                      "name": "client",
                      "dependencies": {
                        "shadcn": "^4.0.5"
                      },
                      "overrides": {
                        "hono": "^4.13.7"
                      }
                    }
                    """);
            // Another override joins the existing ones; an older one of the same package is raised.
            assertThat(editor.override(overridden, "@hono/node-server", "1.19.11", "1.19.12", "shadcn"))
                    .contains("    \"hono\": \"^4.13.7\",\n    \"@hono/node-server\": \"^1.19.12\"\n  }");
            assertThat(editor.override(overridden.replace("^4.13.7", "4.12.0"), "hono", "4.12.7", "4.13.7", null))
                    .isEqualTo(overridden);
            assertThat(editor.override(manifest.replace("\n  }\n}", "\n  },\n  \"overrides\": {}\n}"), "hono",
                    "4.12.7", "4.13.7", null))
                    .endsWith("\"overrides\": {\n    \"hono\": \"^4.13.7\"\n  }\n}\n");

            // Yarn reads resolutions; a version that isn't plain is asked for exactly.
            assertThat(NpmPackageJsonEditor.yarn().override("{\"name\":\"app\"}", "hono", "4.12.7", "4.13.7-rc.1+b1",
                    null)).isEqualTo("{\"name\":\"app\", \"resolutions\": {\"hono\": \"4.13.7-rc.1+b1\"}}");
        }

        @Test
        void refusesAnOverrideItCannotWriteSafely() {
            assertThatThrownBy(() -> editor.override("{\"dependencies\":{\"hono\":\"^4.12.7\"}}", "hono", "4.12.7",
                    "4.13.7", null)).isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("declares hono itself");
            assertThatThrownBy(() -> editor.override("{\"overrides\":{\"hono\":{\".\":\"4.12.7\"}}}", "hono", "4.12.7",
                    "4.13.7", null)).isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("nested rules");
            assertThatThrownBy(() -> editor.override("{\"overrides\":{\"hono\":\"^4.13.7\"}}", "hono", "4.12.7",
                    "4.13.7", null)).isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("only the lockfile");
            assertThatThrownBy(() -> editor.override("{\"overrides\":\"no\"}", "hono", "4.12.7", "4.13.7", null))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("not an object");
        }

        @Test
        void refusesWhatTheManifestDoesNotPinSimply() {
            assertThatThrownBy(() -> editor.upgrade("{\"dependencies\":{\"axios\":\"^0.21.1\"}}", "follow-redirects", "1.14.7", "1.15.4"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("not a direct dependency");
            assertThatThrownBy(() -> editor.upgrade("{\"dependencies\":{\"axios\":\">=0.21.0 <0.22.0\"}}", "axios", "0.21.1", "0.21.2"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("only a plain version");
            assertThatThrownBy(() -> editor.upgrade("{\"dependencies\":{\"axios\":\"^0.21.2\"}}", "axios", "0.21.1", "0.21.2"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("only the lockfile is stale");
            assertThatThrownBy(() -> editor.upgrade("{not json", "axios", "0.21.1", "0.21.2"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("not valid JSON");
        }
    }

    @Nested
    class Pnpm {

        private final PnpmWorkspaceEditor editor = new PnpmWorkspaceEditor();

        @Test
        void overridesInPnpmWorkspaceYamlCreatingTheMapOrTheFileWhenNeeded() {
            assertThat(editor.createsMissing()).isTrue();
            assertThat(editor.override("", "hono", "4.12.7", "4.13.7", "shadcn")).isEqualTo("overrides:\n  hono: ^4.13.7\n");
            // Trading-App's client: pnpm 10's settings, no overrides yet.
            String settings = "ignoredBuiltDependencies:\n  - sharp\n  - unrs-resolver\n";
            assertThat(editor.override(settings, "hono", "4.12.7", "4.13.7", "shadcn"))
                    .isEqualTo(settings + "overrides:\n  hono: ^4.13.7\n");
            assertThat(editor.override("packages:\n  - 'apps/*'", "@hono/node-server", "1.19.11", "1.19.12", null))
                    .isEqualTo("packages:\n  - 'apps/*'\noverrides:\n  '@hono/node-server': ^1.19.12\n");
        }

        @Test
        void joinsOrRaisesTheExistingOverridesAtTheirIndentation() {
            String workspace = """
                    packages:
                      - 'apps/*'
                    overrides:
                        lodash: 4.17.21   # CVE-2021-23337
                        "hono": 4.12.0
                    onlyBuiltDependencies:
                      - esbuild
                    """;
            assertThat(editor.override(workspace, "minimist", "1.2.5", "1.2.6", null))
                    .isEqualTo(workspace.replace("    \"hono\": 4.12.0\n", "    \"hono\": 4.12.0\n    minimist: ^1.2.6\n"));
            assertThat(editor.override(workspace, "hono", "4.12.7", "4.13.7", "shadcn"))
                    .isEqualTo(workspace.replace("    \"hono\": 4.12.0", "    hono: ^4.13.7"));
            assertThat(editor.override("overrides: {}\n", "hono", "4.12.7", "4.13.7", null))
                    .isEqualTo("overrides:\n  hono: ^4.13.7\n");
        }

        @Test
        void refusesWhatItCannotEditSafelyAndLeavesTheLockfileToTheReviewer() {
            assertThatThrownBy(() -> editor.override("overrides: {lodash: 4.17.21}\n", "hono", "4.12.7", "4.13.7", null))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("on one line");
            assertThatThrownBy(() -> editor.override("overrides:\n  hono: ^4.13.7\n", "hono", "4.12.7", "4.13.7", null))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("already overrides");
            assertThatThrownBy(() -> editor.override("- just\n- a list\n", "hono", "4.12.7", "4.13.7", null))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("not a map");
            assertThat(editor.followUps(List.of("client/pnpm-workspace.yaml"))).singleElement().asString()
                    .startsWith("Run `pnpm install` in `client/` and commit the updated `pnpm-lock.yaml`")
                    .contains("pnpm 9 or older, move it to `pnpm.overrides`");
        }
    }

    @Test
    void anOverrideTakesItsPackageManagersFileAndSyntax() {
        UpgradeProposal.VersionOverride pnpm = new UpgradeProposal.VersionOverride("shadcn", "pnpm");
        assertThat(ManifestEditor.forEdit(edit("npm", "client/pnpm-workspace.yaml", pnpm)))
                .isInstanceOf(PnpmWorkspaceEditor.class);
        assertThat(ManifestEditor.forEdit(edit("npm", "package.json", new UpgradeProposal.VersionOverride("x", "yarn")))
                .apply("{}", edit("npm", "package.json", new UpgradeProposal.VersionOverride("x", "yarn"))))
                .contains("\"resolutions\"");
        assertThat(ManifestEditor.forEdit(edit("npm", "package.json", new UpgradeProposal.VersionOverride("x", "npm")))
                .apply("{}", edit("npm", "package.json", new UpgradeProposal.VersionOverride("x", "npm"))))
                .contains("\"overrides\"");
        assertThat(ManifestEditor.forEdit(edit("maven", "pom.xml", new UpgradeProposal.VersionOverride("a:b", null))))
                .isInstanceOf(MavenPomEditor.class);
        assertThat(ManifestEditor.forEdit(edit("npm", "package.json", null))).isInstanceOf(NpmPackageJsonEditor.class);
    }

    private static UpgradeProposal.Edit edit(String ecosystem, String manifest, UpgradeProposal.VersionOverride override) {
        return new UpgradeProposal.Edit(java.util.UUID.randomUUID(), ecosystem, "hono", "4.12.7", "4.13.7", manifest,
                override);
    }

    @Nested
    class Pip {

        private final PipRequirementsEditor editor = new PipRequirementsEditor();

        @Test
        void movesTheExactPinKeepingExtrasMarkersCommentsAndLineEndings() {
            String requirements = "# requests==2.25.1 is vulnerable\r\n"
                    + "Requests[socks] == 2.25.1 ; python_version > \"3.7\"  # pinned\r\n"
                    + "urllib3==1.26.4\r\n";

            assertThat(editor.upgrade(requirements, "requests", "2.25.1", "2.31.0")).isEqualTo(
                    "# requests==2.25.1 is vulnerable\r\n"
                            + "Requests[socks] == 2.31.0 ; python_version > \"3.7\"  # pinned\r\n"
                            + "urllib3==1.26.4\r\n");
        }

        @Test
        void comparesNamesTheWayPep503Does() {
            assertThat(editor.upgrade("zope_interface==5.0.0\n", "Zope.Interface", "5.0.0", "5.4.0"))
                    .isEqualTo("zope_interface==5.4.0\n");
        }

        @Test
        void raisesAnUnpinnedRequirementsLowerBound() {
            assertThat(editor.upgrade("requests>=2.0  # http\n", "requests", "2.25.1", "2.31.0"))
                    .isEqualTo("requests>=2.31.0  # http\n");
            assertThat(editor.upgrade("flask==2.0.1\nRequests[socks] ; python_version > '3'\n", "requests", "2.25.1",
                    "2.31.0")).isEqualTo("flask==2.0.1\nRequests[socks]>=2.31.0 ; python_version > '3'\n");
        }

        @Test
        void refusesWhatTheFileDoesNotPinSimply() {
            assertThatThrownBy(() -> editor.upgrade("requests<3\n", "requests", "2.25.1", "2.31.0"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("a range Aegis doesn't rewrite");
            assertThatThrownBy(() -> editor.upgrade("requests~=2.25\n", "requests", "2.25.1", "2.31.0"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("a range Aegis doesn't rewrite");
            assertThatThrownBy(() -> editor.upgrade("requests>=2.31.0\n", "requests", "2.25.1", "2.31.0"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("already asks for requests >=2.31.0");
            assertThatThrownBy(() -> editor.upgrade("requests==2.25.1\n", "urllib3", "1.26.4", "1.26.5"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("not listed");
            assertThatThrownBy(() -> editor.upgrade("requests==2.26.0\n", "requests", "2.25.1", "2.31.0"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("at 2.26.0, not 2.25.1");
            assertThatThrownBy(() -> editor.upgrade("requests==2.25.1 \\\n    --hash=sha256:abc\n", "requests", "2.25.1", "2.31.0"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("pins hashes");
        }

        @Test
        void overridesAPackageTheFileDoesNotListWithARequirementOfItsOwn() {
            // Pinned like everything else in a fully pinned file; a lower bound otherwise.
            assertThat(editor.override("flask==2.0.1\nrequests==2.25.1\n", "urllib3", "1.26.4", "1.26.18", "requests"))
                    .isEqualTo("flask==2.0.1\nrequests==2.25.1\n"
                            + "urllib3==1.26.18  # pulled in by requests; raised by Aegis to a fixed version\n");
            assertThat(editor.override("flask>=2\r\nrequests==2.25.1", "urllib3", "1.26.4", "1.26.18", null))
                    .isEqualTo("flask>=2\r\nrequests==2.25.1\r\nurllib3>=1.26.18  # raised by Aegis to a fixed version\r\n");
            // Listed after all: raised where it is.
            assertThat(editor.override("urllib3==1.26.4\n", "urllib3", "1.26.4", "1.26.18", "requests"))
                    .isEqualTo("urllib3==1.26.18\n");
            assertThatThrownBy(() -> editor.override("requests==2.25.1 \\\n    --hash=sha256:abc\n", "urllib3",
                    "1.26.4", "1.26.18", null)).isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("pins hashes");
        }
    }
}
