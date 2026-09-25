package com.aegis.core.inventory.sbom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The integration suite's {@link CommandRunner}: fabricates git's checkout and syft's CycloneDX output
 * instead of forking either, so an SBOM import runs end to end without the network. A repository whose
 * name contains {@code private} refuses a clone without credentials, and the token {@code wrong-token} is
 * refused; the generated SBOM holds one npm component named after the repository ({@code <repo>-lib}).
 * A repository whose name contains {@code monorepo} gets syft's real shape instead: an npm project
 * ({@code web/package-lock.json}) and a Maven module ({@code svc/pom.xml}) as components, each declaring
 * libraries — see {@link RepositoryLayout}.
 */
public class FakeGitAndSyft implements CommandRunner {

    /** One recorded git clone: its arguments and the environment git would have seen. */
    public record Clone(List<String> command, Map<String, String> env) {
        public String url() {
            return command.get(command.size() - 2);
        }
    }

    private final List<Clone> clones = Collections.synchronizedList(new ArrayList<>());

    @Override
    public CommandResult run(List<String> command, Path workingDir, Map<String, String> env, Duration timeout)
            throws Exception {
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
            return new CommandResult(0, "", "", false);
        }
        String name = Files.readString(workingDir.resolve("repo").resolve("repository-name"));
        String target = command.stream().filter(argument -> argument.startsWith("cyclonedx-json="))
                .findFirst().orElseThrow().substring("cyclonedx-json=".length());
        Files.writeString(Path.of(target), name.contains("monorepo") ? monorepo(name)
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

    private static String component(String ref, String purl, String foundBy, String location) {
        return "{\"bom-ref\":\"" + ref + "\",\"type\":\"library\",\"name\":\"" + ref + "\",\"purl\":\"" + purl
                + "\",\"properties\":[{\"name\":\"syft:package:foundBy\",\"value\":\"" + foundBy + "\"},"
                + "{\"name\":\"syft:location:0:path\",\"value\":\"" + location + "\"}]}";
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
