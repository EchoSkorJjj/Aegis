package com.aegis.core.inventory.sbom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.aegis.core.inventory.sbom.JsLockfiles.Edge;
import com.aegis.core.inventory.sbom.JsLockfiles.Lockfile;
import com.aegis.core.inventory.sbom.JsLockfiles.Manager;
import com.aegis.core.inventory.sbom.JsLockfiles.Pkg;
import com.aegis.core.inventory.sbom.JsLockfiles.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each JavaScript lockfile format says that syft's SBOM loses: what each project declares, and the optional
 * dependencies (sharp, the platform binaries) syft leaves out of the graph. The fixtures are trimmed from real
 * lockfiles of a Next.js app — next pulls sharp in optionally, shadcn pulls in hono — in each format.
 */
class JsLockfilesTest {

    @TempDir
    Path checkout;

    @Test
    void pnpmSaysWhatEachImporterDeclaresAndKeepsOptionalDependencies() throws Exception {
        write("client/pnpm-lock.yaml", """
                lockfileVersion: '9.0'

                settings:
                  autoInstallPeers: true

                importers:

                  .:
                    dependencies:
                      next:
                        specifier: 16.1.6
                        version: 16.1.6(react@19.2.3)
                      react:
                        specifier: 19.2.3
                        version: 19.2.3
                      shadcn:
                        specifier: ^4.0.5
                        version: 4.0.5(typescript@5.9.3)
                      string-width-cjs:
                        specifier: npm:string-width@^4.2.0
                        version: string-width@4.2.3
                      ui:
                        specifier: workspace:*
                        version: link:packages/ui
                    devDependencies:
                      typescript:
                        specifier: ^5
                        version: 5.9.3

                  packages/ui:
                    dependencies:
                      clsx:
                        specifier: ^2.1.1
                        version: 2.1.1

                packages:

                  next@16.1.6:
                    resolution: {integrity: sha512-x}

                snapshots:

                  next@16.1.6(react@19.2.3):
                    dependencies:
                      react: 19.2.3
                    optionalDependencies:
                      sharp: 0.34.5

                  shadcn@4.0.5(typescript@5.9.3):
                    dependencies:
                      '@modelcontextprotocol/sdk': 1.27.1(zod@3.25.76)

                  '@modelcontextprotocol/sdk@1.27.1(zod@3.25.76)':
                    dependencies:
                      hono: 4.12.7

                  sharp@0.34.5:
                    optionalDependencies:
                      '@img/sharp-darwin-arm64': 0.34.5

                  hono@4.12.7: {}
                """);

        Lockfile lockfile = single();

        assertThat(lockfile.path()).isEqualTo("client/pnpm-lock.yaml");
        assertThat(lockfile.manager()).isEqualTo(Manager.PNPM);
        assertThat(lockfile.projects()).containsExactly(
                new Project("client/package.json", null, Set.of(pkg("next", "16.1.6"), pkg("react", "19.2.3"),
                        pkg("shadcn", "4.0.5"), pkg("string-width", "4.2.3"), pkg("typescript", "5.9.3"))),
                new Project("client/packages/ui/package.json", null, Set.of(pkg("clsx", "2.1.1"))));
        assertThat(lockfile.edges()).contains(
                edge("next", "16.1.6", "sharp", "0.34.5"),
                edge("sharp", "0.34.5", "@img/sharp-darwin-arm64", "0.34.5"),
                edge("shadcn", "4.0.5", "@modelcontextprotocol/sdk", "1.27.1"),
                edge("@modelcontextprotocol/sdk", "1.27.1", "hono", "4.12.7"));
    }

    @Test
    void pnpmBeforeVersion9KeysPackagesByPathAndAnOldLockfileHasNoImporters() throws Exception {
        write("pnpm-lock.yaml", """
                lockfileVersion: 5.4

                specifiers:
                  next: 13.0.0

                dependencies:
                  next: 13.0.0_react@18.2.0

                packages:

                  /next/13.0.0_react@18.2.0:
                    resolution: {integrity: sha512-x}
                    dependencies:
                      react: 18.2.0
                    optionalDependencies:
                      '@next/swc-darwin-arm64': 13.0.0

                  /@next/swc-darwin-arm64/13.0.0:
                    resolution: {integrity: sha512-y}
                """);

        Lockfile lockfile = single();

        assertThat(lockfile.projects()).singleElement()
                .isEqualTo(new Project("package.json", null, Set.of(pkg("next", "13.0.0"))));
        assertThat(lockfile.edges()).containsExactlyInAnyOrder(edge("next", "13.0.0", "react", "18.2.0"),
                edge("next", "13.0.0", "@next/swc-darwin-arm64", "13.0.0"));
    }

    @Test
    void npmResolvesEachDependencyTheWayNodeFindsItAndKnowsItsWorkspaces() throws Exception {
        write("package-lock.json", """
                {"name": "app", "version": "1.0.0", "lockfileVersion": 3, "requires": true, "packages": {
                  "": {"name": "app", "version": "1.0.0", "workspaces": ["packages/*"],
                       "dependencies": {"next": "16.1.6"}, "devDependencies": {"eslint": "^9"}},
                  "packages/web": {"name": "web", "version": "0.1.0", "dependencies": {"axios": "^1.0.0"}},
                  "node_modules/web": {"resolved": "packages/web", "link": true},
                  "node_modules/next": {"version": "16.1.6", "dependencies": {"styled-jsx": "5.1.6"},
                       "optionalDependencies": {"sharp": "^0.34.4"}},
                  "node_modules/sharp": {"version": "0.34.5", "optional": true},
                  "node_modules/styled-jsx": {"version": "5.1.6"},
                  "node_modules/next/node_modules/styled-jsx": {"version": "5.0.0"},
                  "node_modules/eslint": {"version": "9.1.0", "dev": true},
                  "packages/web/node_modules/axios": {"version": "1.7.0", "dependencies": {"follow-redirects": "^1"}},
                  "node_modules/follow-redirects": {"version": "1.15.6"}
                }}
                """);

        Lockfile lockfile = single();

        assertThat(lockfile.manager()).isEqualTo(Manager.NPM);
        assertThat(lockfile.projects()).containsExactly(
                new Project("package.json", pkg("app", "1.0.0"), Set.of(pkg("next", "16.1.6"),
                        pkg("eslint", "9.1.0"))),
                new Project("packages/web/package.json", pkg("web", "0.1.0"), Set.of(pkg("axios", "1.7.0"))));
        // next's own nested copy wins over the hoisted one; sharp is optional and still an edge.
        assertThat(lockfile.edges()).containsExactlyInAnyOrder(
                edge("next", "16.1.6", "styled-jsx", "5.0.0"),
                edge("next", "16.1.6", "sharp", "0.34.5"),
                edge("axios", "1.7.0", "follow-redirects", "1.15.6"));
    }

    @Test
    void npmLockfileVersion1DeclaresThroughThePackageJsonBesideIt() throws Exception {
        write("legacy/package.json", "{\"dependencies\": {\"lodash\": \"^4.17.0\"}, \"devDependencies\": {\"mocha\": \"8\"}}");
        write("legacy/package-lock.json", """
                {"lockfileVersion": 1, "dependencies": {
                  "lodash": {"version": "4.17.20"}, "mocha": {"version": "8.4.0", "dev": true},
                  "debug": {"version": "4.3.1", "dev": true}}}
                """);

        assertThat(single().projects()).singleElement().isEqualTo(new Project("legacy/package.json", null,
                Set.of(pkg("lodash", "4.17.20"), pkg("mocha", "8.4.0"))));
    }

    @Test
    void yarnClassicDeclaresThroughThePackageJsonsOfTheProjectAndItsWorkspaces() throws Exception {
        write("package.json", """
                {"private": true, "workspaces": ["packages/*"], "dependencies": {"next": "16.1.6", "react": "^19"}}
                """);
        write("packages/api/package.json", "{\"name\": \"api\", \"dependencies\": {\"hono\": \"^4.12.0\"}}");
        write("yarn.lock", """
                # THIS IS AN AUTOGENERATED FILE. DO NOT EDIT THIS FILE DIRECTLY.
                # yarn lockfile v1


                next@16.1.6:
                  version "16.1.6"
                  resolved "https://registry.yarnpkg.com/next/-/next-16.1.6.tgz#aaaa"
                  dependencies:
                    react "^19"
                    "@swc/helpers" "0.5.15"
                  optionalDependencies:
                    sharp "^0.34.4"

                react@^19, "react@^19.0.0":
                  version "19.2.3"

                "@swc/helpers@0.5.15":
                  version "0.5.15"

                sharp@^0.34.4:
                  version "0.34.5"

                hono@^4.12.0:
                  version "4.12.7"
                """);

        Lockfile lockfile = single();

        assertThat(lockfile.manager()).isEqualTo(Manager.YARN);
        assertThat(lockfile.projects()).containsExactly(
                new Project("package.json", null, Set.of(pkg("next", "16.1.6"), pkg("react", "19.2.3"))),
                new Project("packages/api/package.json", null, Set.of(pkg("hono", "4.12.7"))));
        assertThat(lockfile.edges()).containsExactlyInAnyOrder(
                edge("next", "16.1.6", "react", "19.2.3"),
                edge("next", "16.1.6", "@swc/helpers", "0.5.15"),
                edge("next", "16.1.6", "sharp", "0.34.5"));
    }

    @Test
    void yarnBerryNamesItsWorkspacesAndWhatTheyDeclare() throws Exception {
        write("yarn.lock", """
                __metadata:
                  version: 8
                  cacheKey: 10c0

                "app@workspace:.":
                  version: 0.0.0-use.local
                  resolution: "app@workspace:."
                  dependencies:
                    next: "npm:16.1.6"
                    web: "workspace:*"
                  languageName: unknown
                  linkType: soft

                "web@workspace:*, web@workspace:packages/web":
                  version: 0.0.0-use.local
                  resolution: "web@workspace:packages/web"
                  dependencies:
                    react: "npm:^19"
                  languageName: unknown
                  linkType: soft

                "next@npm:16.1.6":
                  version: 16.1.6
                  resolution: "next@npm:16.1.6"
                  dependencies:
                    react: "npm:^19"
                    sharp: "npm:^0.34.4"
                  dependenciesMeta:
                    sharp:
                      optional: true

                "react@npm:^19":
                  version: 19.2.3
                  resolution: "react@npm:19.2.3"

                "sharp@npm:^0.34.4":
                  version: 0.34.5
                  resolution: "sharp@npm:0.34.5"
                """);

        Lockfile lockfile = single();

        assertThat(lockfile.projects()).containsExactly(
                new Project("package.json", pkg("app", "0.0.0-use.local"), Set.of(pkg("next", "16.1.6"))),
                new Project("packages/web/package.json", pkg("web", "0.0.0-use.local"),
                        Set.of(pkg("react", "19.2.3"))));
        assertThat(lockfile.edges()).containsExactlyInAnyOrder(edge("next", "16.1.6", "react", "19.2.3"),
                edge("next", "16.1.6", "sharp", "0.34.5"));
    }

    @Test
    void readsEveryLockfileOutsideNodeModulesAndSkipsOneItCannotRead() throws Exception {
        write("client/pnpm-lock.yaml", "lockfileVersion: '9.0'\nimporters:\n  .: {}\n");
        write("server/package-lock.json", "{\"lockfileVersion\": 3, \"packages\": {\"\": {}}}");
        write("client/node_modules/dep/package-lock.json", "{\"lockfileVersion\": 3, \"packages\": {\"\": {}}}");
        write("broken/yarn.lock", "__metadata:\n  version: 8\n\"a@npm:1\": [unclosed\n");
        write("bad/package-lock.json", "{not json");

        assertThat(JsLockfiles.read(checkout)).extracting(Lockfile::path)
                .containsExactly("client/pnpm-lock.yaml", "server/package-lock.json");
    }

    @Test
    void aVersionThatIsNotARegistrysIsNoPackage() {
        assertThat(JsLockfiles.pnpmPackage("ui", "link:../ui", 9)).isNull();
        assertThat(JsLockfiles.pnpmPackage("x", "file:x.tgz", 9)).isNull();
        assertThat(JsLockfiles.pnpmPackage("x", "https://example.test/x.tgz", 9)).isNull();
        assertThat(JsLockfiles.pnpmKey("/@babel/core/7.0.0_supports-color@5.5.0", 5)).isEqualTo(pkg("@babel/core", "7.0.0"));
        assertThat(JsLockfiles.pnpmKey("/@babel/core@7.0.0(supports-color@5.5.0)", 6)).isEqualTo(pkg("@babel/core", "7.0.0"));
    }

    private Lockfile single() {
        List<Lockfile> lockfiles = JsLockfiles.read(checkout);
        assertThat(lockfiles).hasSize(1);
        return lockfiles.getFirst();
    }

    private void write(String path, String content) throws Exception {
        Path file = checkout.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Pkg pkg(String name, String version) {
        return new Pkg(name, version);
    }

    private static Edge edge(String from, String fromVersion, String to, String toVersion) {
        return new Edge(pkg(from, fromVersion), pkg(to, toVersion));
    }
}
