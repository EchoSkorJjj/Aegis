package com.aegis.core.inventory.sbom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The integration suite's {@link CommandRunner}: fabricates git's checkout and syft's CycloneDX output
 * instead of forking either, so an SBOM import runs end to end without the network. A repository whose
 * name contains {@code private} refuses a clone without credentials, and the token {@code wrong-token} is
 * refused; the generated SBOM holds one npm component named after the repository ({@code <repo>-lib}).
 * A repository whose name contains {@code monorepo} gets syft's real shape instead: an npm project
 * ({@code web/package-lock.json}) and a Maven module ({@code svc/pom.xml}) as components, each declaring
 * libraries — see {@link RepositoryLayout}. One whose name contains {@code pnpmapp} has a pnpm lockfile in its
 * checkout, which syft's graph of it misreads ({@link #pnpmApp}). A test can change what a repository holds
 * ({@link #willScan}).
 */
public class FakeGitAndSyft implements CommandRunner {

    /** One recorded git clone: its arguments and the environment git would have seen. */
    public record Clone(List<String> command, Map<String, String> env) {
        public String url() {
            return command.get(command.size() - 2);
        }
    }

    private final List<Clone> clones = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> scans = new ConcurrentHashMap<>();

    /** From now on syft reports {@code sbom} for the repository named {@code name}: the repository changed. */
    public void willScan(String name, String sbom) {
        scans.put(name, sbom);
    }

    @Override
    public CommandResult run(List<String> command, Path workingDir, Map<String, String> env, Duration timeout)
            throws Exception {
        if (command.get(0).equals("git") && !command.contains("clone")) {
            return new CommandResult(0, "", "", false); // the sparse checkout and the checkout
        }
        if (command.get(0).equals("git")) {
            Clone clone = new Clone(List.copyOf(command), Map.copyOf(env));
            clones.add(clone);
            String url = clone.url();
            if (url.contains("private") && !env.containsKey("GIT_ASKPASS")) {
                return new CommandResult(128, "", "Cloning into 'repo'...\n"
                        + "fatal: could not read Username for 'https://github.com': terminal prompts disabled", false);
            }
            if ("wrong-token".equals(env.get("AEGIS_GIT_TOKEN"))) {
                return new CommandResult(128, "", "fatal: Authentication failed for '" + url + "'", false);
            }
            Path checkout = Path.of(command.get(command.size() - 1));
            Files.createDirectories(checkout);
            String name = url.substring(url.lastIndexOf('/') + 1).replace(".git", "");
            Files.writeString(checkout.resolve("repository-name"), name);
            if (name.contains("pnpmapp")) {
                Files.createDirectories(checkout.resolve("client"));
                Files.writeString(checkout.resolve("client/pnpm-lock.yaml"), pnpmLockfile(name));
            }
            return new CommandResult(0, "", "", false);
        }
        String name = Files.readString(workingDir.resolve("repo").resolve("repository-name"));
        String target = command.stream().filter(argument -> argument.startsWith("cyclonedx-json="))
                .findFirst().orElseThrow().substring("cyclonedx-json=".length());
        Files.writeString(Path.of(target), scans.containsKey(name) ? scans.get(name)
                : name.contains("monorepo") ? monorepo(name)
                : name.contains("twolocks") ? twoLockfiles(name)
                : name.contains("pnpmapp") ? pnpmApp(name)
                : "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":["
                        + "{\"type\":\"library\",\"name\":\"" + name + "-lib\",\"version\":\"1.0.0\","
                        + "\"purl\":\"pkg:npm/" + name + "-lib@1.0.0\"}]}");
        return new CommandResult(0, "", "", false);
    }

    /**
     * syft's shape for a repository with an npm project and a Maven module: {@code <name>-web} (the
     * package-lock root) declares {@code <name>-http}, which needs {@code <name>-util}; {@code <name>-svc}
     * (the module) declares {@code com.acme:<name>-core} and a parent-managed {@code <name>-managed}.
     */
    static String monorepo(String name) {
        return """
                {"bomFormat":"CycloneDX","specVersion":"1.6",
                 "metadata":{"component":{"bom-ref":"dir","type":"file","name":"/scan/repo"}},
                 "components":[
                  %s, %s, %s, %s, %s, %s,
                  {"bom-ref":"lockfile","type":"file","name":"/scan/repo/web/package-lock.json"}],
                 "dependencies":[
                  {"ref":"web","dependsOn":["http"]},
                  {"ref":"http","dependsOn":["util"]},
                  {"ref":"svc","dependsOn":["core","managed"]}]}
                """.formatted(
                component("web", "pkg:npm/" + name + "-web@0.0.0", "javascript-lock-cataloger", "/web/package-lock.json"),
                component("http", "pkg:npm/" + name + "-http@1.0.0", "javascript-lock-cataloger", "/web/package-lock.json"),
                component("util", "pkg:npm/" + name + "-util@2.0.0", "javascript-lock-cataloger", "/web/package-lock.json"),
                component("svc", "pkg:maven/com.acme/" + name + "-svc@1.0.0", "java-pom-cataloger", "/svc/pom.xml"),
                component("core", "pkg:maven/com.acme/" + name + "-core@3.0.0", "java-pom-cataloger", "/svc/pom.xml"),
                component("managed", "pkg:maven/com.acme/" + name + "-managed", "java-pom-cataloger", "/svc/pom.xml")
                        .replace("\"purl\"", "\"version\":\"UNKNOWN\",\"purl\""));
    }

    /**
     * syft's shape for a pnpm project in {@code client/}: its packages, with no project and no root edges, and
     * {@code <name>-next}'s optional {@code <name>-sharp} left out of the graph — what {@link #pnpmLockfile} says.
     */
    static String pnpmApp(String name) {
        return """
                {"bomFormat":"CycloneDX","specVersion":"1.6",
                 "metadata":{"component":{"bom-ref":"dir","type":"file","name":"/scan/repo"}},
                 "components":[%s, %s, %s],
                 "dependencies":[{"ref":"next","dependsOn":["react"]}]}
                """.formatted(
                versioned("next", name + "-next", "16.1.6", "/client/pnpm-lock.yaml"),
                versioned("react", name + "-react", "19.2.3", "/client/pnpm-lock.yaml"),
                versioned("sharp", name + "-sharp", "0.34.5", "/client/pnpm-lock.yaml"));
    }

    /** The lockfile behind {@link #pnpmApp}: the project declares next and react; next pulls sharp in, optionally. */
    static String pnpmLockfile(String name) {
        return """
                lockfileVersion: '9.0'

                importers:

                  .:
                    dependencies:
                      %1$s-next:
                        specifier: 16.1.6
                        version: 16.1.6(%1$s-react@19.2.3)
                      %1$s-react:
                        specifier: 19.2.3
                        version: 19.2.3

                snapshots:

                  %1$s-next@16.1.6(%1$s-react@19.2.3):
                    dependencies:
                      %1$s-react: 19.2.3
                    optionalDependencies:
                      %1$s-sharp: 0.34.5

                  %1$s-react@19.2.3: {}

                  %1$s-sharp@0.34.5: {}
                """.formatted(name);
    }

    private static String versioned(String ref, String name, String version, String location) {
        return component(ref, "pkg:npm/" + name + "@" + version, "javascript-lock-cataloger", location)
                .replace("\"name\":\"" + ref + "\"", "\"name\":\"" + name + "\",\"version\":\"" + version + "\"");
    }

    /**
     * syft's shape for a repository with two npm projects that pin the same library version: {@code <name>-http}
     * 1.0.0 is reported once, found in both lockfiles, and both project roots depend on it.
     */
    static String twoLockfiles(String name) {
        return """
                {"bomFormat":"CycloneDX","specVersion":"1.6",
                 "metadata":{"component":{"bom-ref":"dir","type":"file","name":"/scan/repo"}},
                 "components":[%s, %s, %s],
                 "dependencies":[
                  {"ref":"client","dependsOn":["http"]},
                  {"ref":"server","dependsOn":["http"]}]}
                """.formatted(
                component("client", "pkg:npm/" + name + "-client@0.0.0", "javascript-lock-cataloger",
                        "/client/package-lock.json"),
                component("server", "pkg:npm/" + name + "-server@0.0.0", "javascript-lock-cataloger",
                        "/server/package-lock.json"),
                component("http", "pkg:npm/" + name + "-http@1.0.0", "javascript-lock-cataloger",
                        List.of("/client/package-lock.json", "/server/package-lock.json")));
    }

    /**
     * syft's shape for npm projects whose lockfiles each pin a version of {@code <name>-http}, by the project's
     * directory ({@code client} → {@code 1.5.0}): a version pinned in several lockfiles is reported once, found
     * in each.
     */
    public static String npmProjects(String name, Map<String, String> httpVersionByProject) {
        List<String> components = new ArrayList<>();
        List<String> dependencies = new ArrayList<>();
        Map<String, List<String>> lockfilesByVersion = new LinkedHashMap<>();
        // By path, as syft reports them — Map.of's own order changes from one JVM to the next.
        new TreeMap<>(httpVersionByProject).forEach((project, version) -> {
            String lockfile = "/" + project + "/package-lock.json";
            components.add(component(project, "pkg:npm/" + name + "-" + project + "@0.0.0", "javascript-lock-cataloger",
                    lockfile));
            dependencies.add("{\"ref\":\"" + project + "\",\"dependsOn\":[\"http-" + version + "\"]}");
            lockfilesByVersion.computeIfAbsent(version, pinned -> new ArrayList<>()).add(lockfile);
        });
        lockfilesByVersion.forEach((version, lockfiles) -> components.add(component("http-" + version,
                "pkg:npm/" + name + "-http@" + version, "javascript-lock-cataloger", lockfiles)));
        return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
                + "\"metadata\":{\"component\":{\"bom-ref\":\"dir\",\"type\":\"file\",\"name\":\"/scan/repo\"}},"
                + "\"components\":[" + String.join(",", components) + "],"
                + "\"dependencies\":[" + String.join(",", dependencies) + "]}";
    }

    /**
     * syft's shape for one npm project whose lockfile pins {@code <name>-http} 1.0.0 and, below it,
     * {@code <name>-redirects} at {@code redirects}: refreshing the lockfile moves the transitive one only.
     */
    public static String httpWithRedirects(String name, String redirects) {
        return """
                {"bomFormat":"CycloneDX","specVersion":"1.6",
                 "metadata":{"component":{"bom-ref":"dir","type":"file","name":"/scan/repo"}},
                 "components":[%s, %s, %s],
                 "dependencies":[{"ref":"app","dependsOn":["http"]},{"ref":"http","dependsOn":["redirects"]}]}
                """.formatted(
                component("app", "pkg:npm/" + name + "-app@0.0.0", "javascript-lock-cataloger", "/package-lock.json"),
                component("http", "pkg:npm/" + name + "-http@1.0.0", "javascript-lock-cataloger", "/package-lock.json"),
                component("redirects", "pkg:npm/" + name + "-redirects@" + redirects, "javascript-lock-cataloger",
                        "/package-lock.json"));
    }

    private static String component(String ref, String purl, String foundBy, String location) {
        return component(ref, purl, foundBy, List.of(location));
    }

    private static String component(String ref, String purl, String foundBy, List<String> locations) {
        StringBuilder properties = new StringBuilder("{\"name\":\"syft:package:foundBy\",\"value\":\"" + foundBy + "\"}");
        for (int i = 0; i < locations.size(); i++) {
            properties.append(",{\"name\":\"syft:location:").append(i).append(":path\",\"value\":\"")
                    .append(locations.get(i)).append("\"}");
        }
        return "{\"bom-ref\":\"" + ref + "\",\"type\":\"library\",\"name\":\"" + ref + "\",\"purl\":\"" + purl
                + "\",\"properties\":[" + properties + "]}";
    }

    /** The latest clone of a URL containing {@code fragment} (a test's own unique repository name). */
    public Clone lastCloneOf(String fragment) {
        synchronized (clones) {
            for (int i = clones.size() - 1; i >= 0; i--) {
                if (clones.get(i).url().contains(fragment)) {
                    return clones.get(i);
                }
            }
        }
        throw new AssertionError("no clone of " + fragment);
    }
}
