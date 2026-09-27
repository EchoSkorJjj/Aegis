package com.aegis.core.inventory.sbom;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.aegis.core.inventory.sbom.CycloneDxBom.Component;
import com.aegis.core.inventory.sbom.CycloneDxBom.Dependency;
import com.aegis.core.inventory.sbom.CycloneDxBom.Metadata;
import com.aegis.core.inventory.sbom.CycloneDxBom.Property;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * syft reports a repository's own packages as components; {@link RepositoryLayout} turns them back into the
 * repository. The fixture is syft 1.52's real output for a repository with an npm project
 * ({@code client/package-lock.json}) and a Maven module ({@code service/pom.xml}).
 */
class RepositoryLayoutTest {

    @Test
    void theRepositorysOwnPackagesBecomeTheRepositoryAndWhatTheyDeclareIsDirect() throws Exception {
        RepositoryLayout layout = RepositoryLayout.of(fixture());

        assertThat(layout.projects()).extracting(Component::name).containsExactlyInAnyOrder("client", "service");
        assertThat(layout.bom().components()).extracting(Component::name).doesNotContain("client", "service")
                .contains("axios", "follow-redirects", "log4j-core", "spring-boot-starter-web");
        String root = layout.bom().metadata().component().ref();
        assertThat(names(layout, dependsOn(layout, root)))
                .containsExactlyInAnyOrder("axios", "log4j-core", "spring-boot-starter-web");
        // Below a direct dependency nothing changes: follow-redirects still comes in through axios.
        assertThat(names(layout, dependsOn(layout, ref(layout, "axios")))).containsExactly("follow-redirects");
        assertThat(layout.bom().dependencies()).noneMatch(dependency -> layout.projects().stream()
                .anyMatch(project -> project.ref().equals(dependency.ref())));
    }

    @Test
    void eachDirectDependencyRemembersTheManifestThatDeclaresIt() throws Exception {
        RepositoryLayout layout = RepositoryLayout.of(fixture());

        Map<String, List<String>> byName = layout.manifestsByRef().entrySet().stream()
                .collect(Collectors.toMap(entry -> name(layout, entry.getKey()), Map.Entry::getValue));
        assertThat(byName).containsEntry("axios", List.of("client/package.json"))
                .containsEntry("log4j-core", List.of("service/pom.xml"))
                .containsEntry("spring-boot-starter-web", List.of("service/pom.xml"))
                .doesNotContainKey("follow-redirects");
    }

    @Test
    void theSameVersionInTwoProjectsRemembersBothManifests() {
        // syft reports axios 1.6.0 once, found in both lockfiles; both projects depend on it.
        Component client = syft("pkg:npm/client@0.0.0", "client", "0.0.0", "javascript-lock-cataloger",
                "/client/package-lock.json");
        Component server = syft("pkg:npm/server@0.0.0", "server", "0.0.0", "javascript-lock-cataloger",
                "/server/package-lock.json");
        Component axios = new Component("pkg:npm/axios@1.6.0", "library", null, "axios", "1.6.0",
                "pkg:npm/axios@1.6.0", null, List.of(new Property("syft:package:foundBy", "javascript-lock-cataloger"),
                        new Property("syft:location:0:path", "/client/package-lock.json"),
                        new Property("syft:location:1:path", "/server/package-lock.json")));
        CycloneDxBom bom = new CycloneDxBom("CycloneDX", "1.6", new Metadata(directory()), List.of(client, server, axios),
                List.of(new Dependency(client.ref(), List.of(axios.ref())),
                        new Dependency(server.ref(), List.of(axios.ref()))));

        RepositoryLayout layout = RepositoryLayout.of(bom);

        assertThat(layout.manifestsByRef())
                .containsOnly(Map.entry(axios.ref(), List.of("client/package.json", "server/package.json")));
    }

    @Test
    void aLibraryFoundAtSeveralLocationsIsDeclaredInEach() {
        Component lib = new Component("lib", "library", null, "lib", "1.0.0", "pkg:npm/lib@1.0.0", null, List.of(
                new Property("syft:location:1:path", "/web/yarn.lock"),
                new Property("syft:location:0:path", "/api/package-lock.json"),
                new Property("syft:location:2:path", "/api/package-lock.json"),
                new Property("syft:location:3:path", "/docs/build.gradle")));

        assertThat(RepositoryLayout.manifestsOf(lib)).containsExactly("api/package.json", "web/package.json",
                "docs/build.gradle");
    }

    @Test
    void aPnpmOrYarnProjectNamesNoRootSoItsLibrariesStayAsTheyAre() {
        Component next = syft("pkg:npm/next@16.0.0", "next", "16.0.0", "javascript-lock-cataloger",
                "/frontend/pnpm-lock.yaml");
        Component env = syft("pkg:npm/@next/env@16.0.0", "@next/env", "16.0.0", "javascript-lock-cataloger",
                "/frontend/pnpm-lock.yaml");
        CycloneDxBom bom = new CycloneDxBom("CycloneDX", "1.6", new Metadata(directory()), List.of(next, env),
                List.of(new Dependency(next.ref(), List.of(env.ref()))));

        RepositoryLayout layout = RepositoryLayout.of(bom);

        assertThat(layout.projects()).isEmpty();
        assertThat(layout.bom()).isSameAs(bom);
        assertThat(layout.manifestsByRef()).containsOnly(Map.entry(next.ref(), List.of("frontend/package.json")));
    }

    @Test
    void anSbomFromAnotherToolIsLeftAlone() {
        Component app = new Component("app", "application", null, "app", "1.0.0", null, null);
        Component lib = new Component("lib", "library", null, "lib", "2.0.0", "pkg:npm/lib@2.0.0", null);
        CycloneDxBom bom = new CycloneDxBom("CycloneDX", "1.5", new Metadata(app), List.of(lib),
                List.of(new Dependency("app", List.of("lib"))));

        RepositoryLayout layout = RepositoryLayout.of(bom);

        assertThat(layout.projects()).isEmpty();
        assertThat(layout.bom()).isSameAs(bom);
        assertThat(layout.manifestsByRef()).isEmpty();
    }

    @Test
    void aProjectNeedsToBeARootThatDependsOnSomethingAtItsOwnManifest() {
        // A declared Maven dependency sits at the same pom.xml but depends on nothing (syft resolves no
        // Maven transitives offline); only the module itself has outgoing edges.
        Component module = syft("pkg:maven/com.acme/api@1.0.0", "api", "1.0.0", "java-pom-cataloger", "/api/pom.xml");
        Component lonely = syft("pkg:maven/com.acme/tool@1.0.0", "tool", "1.0.0", "java-pom-cataloger", "/tool/pom.xml");
        Component lib = syft("pkg:maven/org.acme/lib@2.0.0", "lib", "2.0.0", "java-pom-cataloger", "/api/pom.xml");
        CycloneDxBom bom = new CycloneDxBom("CycloneDX", "1.6", null, List.of(module, lonely, lib),
                List.of(new Dependency(module.ref(), List.of(lib.ref()))));

        RepositoryLayout layout = RepositoryLayout.of(bom);

        assertThat(layout.projects()).extracting(Component::name).containsExactly("api");
        // No root in the document: the repository gets one, and the module's dependency hangs on it.
        assertThat(layout.bom().metadata().component().ref()).isEqualTo(RepositoryLayout.SYNTHETIC_ROOT);
        assertThat(dependsOn(layout, RepositoryLayout.SYNTHETIC_ROOT)).containsExactly(lib.ref());
        assertThat(layout.manifestsByRef()).containsEntry(lib.ref(), List.of("api/pom.xml"));
    }

    @Test
    void aPackageRemembersTheFileDeclaringItEvenOneAegisDoesNotEdit() {
        assertThat(RepositoryLayout.manifestOf(located("/requirements.txt"))).isEqualTo("requirements.txt");
        assertThat(RepositoryLayout.manifestOf(located("/web/yarn.lock"))).isEqualTo("web/package.json");
        assertThat(RepositoryLayout.manifestOf(located("/package-lock.json"))).isEqualTo("package.json");
        // Recorded so an upgrade knows the dependency lives there, and never guesses a root pom.xml instead.
        assertThat(RepositoryLayout.manifestOf(located("/svc/gradle.lockfile"))).isEqualTo("svc/gradle.lockfile");
        assertThat(RepositoryLayout.manifestOf(located("/poetry.lock"))).isEqualTo("poetry.lock");
        assertThat(RepositoryLayout.manifestOf(located("/lib/vendored.jar"))).isEqualTo("lib/vendored.jar");
        assertThat(RepositoryLayout.manifestOf(located("/" + "d/".repeat(200) + "x.lock"))).isNull();
        assertThat(RepositoryLayout.manifestOf(located(null))).isNull();
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static CycloneDxBom fixture() throws Exception {
        try (InputStream json = RepositoryLayoutTest.class.getResourceAsStream("/sbom/syft-repository.cdx.json")) {
            return new ObjectMapper().readValue(json, CycloneDxBom.class);
        }
    }

    private static Component directory() {
        return new Component("dir-root", "file", null, "/scan/repo", null, null, null);
    }

    private static Component syft(String purl, String name, String version, String foundBy, String location) {
        return new Component(purl, "library", null, name, version, purl, null, List.of(
                new Property("syft:package:foundBy", foundBy), new Property("syft:location:0:path", location)));
    }

    private static Component located(String location) {
        return new Component("x", "library", null, "x", "1", null, null,
                location == null ? null : List.of(new Property("syft:location:0:path", location)));
    }

    private static List<String> dependsOn(RepositoryLayout layout, String ref) {
        return layout.bom().dependencies().stream().filter(dependency -> dependency.ref().equals(ref))
                .flatMap(dependency -> dependency.dependsOn().stream()).toList();
    }

    private static Set<String> names(RepositoryLayout layout, List<String> refs) {
        return refs.stream().map(ref -> name(layout, ref)).collect(Collectors.toSet());
    }

    private static String name(RepositoryLayout layout, String ref) {
        return layout.bom().components().stream().filter(component -> ref.equals(component.ref()))
                .map(Component::name).findFirst().orElse(ref);
    }

    private static String ref(RepositoryLayout layout, String name) {
        return layout.bom().components().stream().filter(component -> name.equals(component.name()))
                .map(Component::ref).findFirst().orElseThrow();
    }
}
