package com.aegis.core.actions.upgrade;

import com.aegis.core.platform.common.ApiExceptions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestEditorTest {

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
            assertThatThrownBy(() -> editor.upgrade(managed, LOG4J, "2.14.1", "2.17.1"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("without a version");

            String elsewhere = managed.replace("</artifactId>", "</artifactId><version>2.13.0</version>");
            assertThatThrownBy(() -> editor.upgrade(elsewhere, LOG4J, "2.14.1", "2.17.1"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("at 2.13.0, not 2.14.1");

            String inheritedProperty = managed.replace("</artifactId>", "</artifactId><version>${log4j.version}</version>");
            assertThatThrownBy(() -> editor.upgrade(inheritedProperty, LOG4J, "2.14.1", "2.17.1"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("does not set");

            assertThatThrownBy(() -> editor.upgrade(managed, "log4j-core", "2.14.1", "2.17.1"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("groupId:artifactId");
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
            assertThat(editor.followUps()).singleElement().asString().contains("package-lock.json");
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
        void refusesWhatTheFileDoesNotPinExactly() {
            assertThatThrownBy(() -> editor.upgrade("requests>=2.0\n", "requests", "2.25.1", "2.31.0"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("does not pin");
            assertThatThrownBy(() -> editor.upgrade("requests==2.25.1\n", "urllib3", "1.26.4", "1.26.5"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("not listed");
            assertThatThrownBy(() -> editor.upgrade("requests==2.26.0\n", "requests", "2.25.1", "2.31.0"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("at 2.26.0, not 2.25.1");
            assertThatThrownBy(() -> editor.upgrade("requests==2.25.1 \\\n    --hash=sha256:abc\n", "requests", "2.25.1", "2.31.0"))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("pins hashes");
        }
    }
}
