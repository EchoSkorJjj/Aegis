package com.aegis.core.actions.upgrade;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aegis.core.platform.common.ApiExceptions;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Raises a direct dependency in a {@code package.json} to the fixed version, keeping its range operator:
 * {@code "^0.21.1"} becomes {@code "^0.21.2"}, so the manifest can no longer resolve a vulnerable
 * release. Only a plain version, or one behind {@code ^}, {@code ~}, {@code >=} or {@code =}, is edited;
 * a tag, URL, wildcard or compound range is refused.
 *
 * <p>A package the project doesn't declare — one a dependency pulls in — is overridden instead: npm's
 * {@code overrides} ({@link #NpmPackageJsonEditor()}) or yarn's {@code resolutions} ({@link #yarn()}) in the
 * package.json of the project that the lockfile belongs to, the only one either reads. The override names the package
 * alone, so every copy of it in the tree moves to the fixed version, at {@code ^} that version so later fixes still
 * arrive. (pnpm reads its overrides from pnpm-workspace.yaml: {@link PnpmWorkspaceEditor}.)
 */
final class NpmPackageJsonEditor implements ManifestEditor {

    private static final List<String> SECTIONS =
            List.of("dependencies", "devDependencies", "optionalDependencies", "peerDependencies");
    private static final Pattern SIMPLE_SPEC = Pattern.compile("(\\^|~|>=|=)?v?\\d[0-9A-Za-z.+-]*");
    private static final Pattern PLAIN_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    /** The field an override is written to, and the tool that installs the project. */
    private final String overrides;
    private final String tool;

    /** npm's: an override goes to {@code overrides}. */
    NpmPackageJsonEditor() {
        this("overrides", "npm");
    }

    private NpmPackageJsonEditor(String overrides, String tool) {
        this.overrides = overrides;
        this.tool = tool;
    }

    /** Yarn's, classic and berry: an override goes to {@code resolutions}. */
    static NpmPackageJsonEditor yarn() {
        return new NpmPackageJsonEditor("resolutions", "yarn");
    }

    @Override
    public String fileName() {
        return "package.json";
    }

    /** The version an override asks for: {@code ^} the fixed one, so later fixes in its line still arrive. */
    static String overrideRange(String toVersion) {
        return PLAIN_VERSION.matcher(toVersion).matches() ? "^" + toVersion : toVersion;
    }

    @Override
    public String override(String manifest, String packageName, String fromVersion, String toVersion, String via) {
        Map<String, Object> root = parse(manifest);
        for (String section : SECTIONS) {
            if (root.get(section) instanceof Map<?, ?> dependencies && dependencies.containsKey(packageName)) {
                throw new ApiExceptions.Conflict("package.json declares " + packageName + " itself, so its declaration "
                        + "is what to raise; re-import the service's SBOM and Aegis bumps it instead");
            }
        }
        String range = overrideRange(toVersion);
        Object existing = root.get(overrides);
        if (existing != null && !(existing instanceof Map<?, ?>)) {
            throw new ApiExceptions.Conflict("package.json's \"" + overrides + "\" is not an object; add the "
                    + packageName + " override by hand");
        }
        JsonText.ObjectSpan top = JsonText.root(manifest);
        String unit = JsonText.unit(manifest, top);
        String member = JsonText.string(packageName) + ": " + JsonText.string(range);
        String result;
        if (existing instanceof Map<?, ?> current) {
            JsonText.ObjectSpan block = JsonText.object(manifest, top.member(overrides).valueStart());
            if (current.containsKey(packageName)) {
                Object value = current.get(packageName);
                if (!(value instanceof String spec)) {
                    throw new ApiExceptions.Conflict("package.json already overrides " + packageName
                            + " with nested rules; raise it to " + toVersion + " there by hand");
                }
                if (spec.equals(range)) {
                    throw new ApiExceptions.Conflict("package.json already overrides " + packageName + " to " + range
                            + "; only the lockfile is stale, so run `" + tool + " install` instead");
                }
                JsonText.Member entry = block.member(packageName);
                result = manifest.substring(0, entry.valueStart()) + JsonText.string(range)
                        + manifest.substring(entry.valueEnd());
            } else {
                result = JsonText.append(manifest, block, member, unit);
            }
        } else {
            String indent = top.members().isEmpty() ? "" : JsonText.indentOf(manifest, top.members().getFirst().keyStart());
            String newline = manifest.contains("\r\n") ? "\r\n" : "\n";
            boolean oneLine = manifest.substring(top.open(), top.close()).indexOf('\n') < 0;
            String block = oneLine ? "{" + member + "}"
                    : "{" + newline + indent + unit + member + newline + indent + "}";
            result = JsonText.append(manifest, top, JsonText.string(overrides) + ": " + block, unit);
        }
        // The text edit must read back as exactly the override asked for, and nothing else moved.
        Map<String, Object> written = parse(result);
        Map<String, Object> expected = new LinkedHashMap<>(root);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existing instanceof Map<?, ?> current) {
            current.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }
        merged.put(packageName, range);
        expected.put(overrides, merged);
        if (!written.equals(expected)) {
            throw new ApiExceptions.Conflict("package.json is laid out in a way Aegis can't edit safely; override "
                    + packageName + " to " + range + " by hand");
        }
        return result;
    }

    /**
     * The lockfile: Aegis edits {@code package.json} only, and the lockfile beside it keeps pinning the old version
     * until someone refreshes it — which is what installs read, and what the next SBOM import reports.
     */
    @Override
    public List<String> followUps(List<String> manifestPaths) {
        // In path order, the repository root first, however the edits happen to be ordered.
        List<String> directories = manifestPaths.stream()
                .map(path -> path.substring(0, path.lastIndexOf('/') + 1))
                .distinct()
                .sorted()
                .map(directory -> directory.isEmpty() ? "the repository root" : UpgradePullRequestText.code(directory))
                .toList();
        if (directories.isEmpty()) {
            return List.of();
        }
        boolean several = directories.size() > 1;
        return List.of("Refresh the lockfile" + (several ? "s" : "") + " in " + UpgradePullRequestText.and(directories)
                + " and commit " + (several ? "them" : "it") + " to this branch: run `npm install` there (`yarn install` "
                + "or `pnpm install` in a yarn or pnpm project). Until then the lockfile still pins the old version, so "
                + "`npm ci` refuses to install and Aegis keeps reporting the vulnerability.");
    }

    @Override
    public String upgrade(String manifest, String packageName, String fromVersion, String toVersion) {
        Set<String> specs = new LinkedHashSet<>();
        for (Object section : sections(manifest)) {
            if (section instanceof Map<?, ?> dependencies && dependencies.get(packageName) instanceof String spec) {
                specs.add(spec);
            }
        }
        if (specs.isEmpty()) {
            throw new ApiExceptions.Conflict(packageName + " is not a direct dependency in package.json: it comes in "
                    + "transitively, so upgrade the package that depends on it instead");
        }
        String result = manifest;
        Set<String> changed = new LinkedHashSet<>();
        String declared = null;
        for (String spec : specs) {
            Matcher simple = SIMPLE_SPEC.matcher(spec.trim());
            if (!simple.matches()) {
                throw new ApiExceptions.Conflict("package.json asks for " + packageName + " as \"" + spec + "\"; only a "
                        + "plain version, or one behind ^, ~, >= or =, can be upgraded automatically");
            }
            String replacement = (simple.group(1) == null ? "" : simple.group(1)) + toVersion;
            if (declared == null) {
                declared = replacement;
            }
            if (replacement.equals(spec)) {
                continue;
            }
            Pattern entry = Pattern.compile("(\"" + Pattern.quote(packageName) + "\"\\s*:\\s*\")" + Pattern.quote(spec) + "\"");
            result = entry.matcher(result).replaceAll("$1" + Matcher.quoteReplacement(replacement) + "\"");
            changed.add(spec);
        }
        if (changed.isEmpty()) {
            // The declaration is there already; an override left at the old version may be what still holds it back.
            String followed = followingOverrides(manifest, packageName, declared);
            if (!followed.equals(manifest)) {
                return followed;
            }
            throw new ApiExceptions.Conflict("package.json already asks for " + packageName + " " + toVersion
                    + "; only the lockfile is stale, so run `npm install` instead");
        }
        // The text edit must land exactly where the parse found the dependency, or nothing is written.
        for (Object section : sections(result)) {
            if (section instanceof Map<?, ?> dependencies && dependencies.get(packageName) instanceof String spec
                    && changed.contains(spec)) {
                throw new ApiExceptions.Conflict("package.json spells " + packageName + " in a way that cannot be "
                        + "rewritten safely; upgrade it by hand");
            }
        }
        return followingOverrides(result, packageName, declared);
    }

    /**
     * An override of a declared package in a project's root package.json — a workspace's, whose declaration moves in
     * the workspace's own — raised with it: to the root's own declaration of it when it has one (npm takes an
     * override of a direct dependency only at the same spec), else to {@code ^} the fixed version.
     */
    @Override
    public String raiseOverride(String manifest, String packageName, String toVersion) {
        String declared = null;
        for (Object section : sections(manifest)) {
            if (declared == null && section instanceof Map<?, ?> dependencies
                    && dependencies.get(packageName) instanceof String spec) {
                declared = spec;
            }
        }
        return followingOverrides(manifest, packageName, declared != null ? declared : overrideRange(toVersion));
    }

    /**
     * The package.json with every override of {@code packageName} it holds — npm's {@code overrides}, yarn's
     * {@code resolutions}, {@code pnpm.overrides} before pnpm 10 — set to {@code spec}, so none keeps the old version
     * (npm refuses an override of a direct dependency that differs from its declaration). An override written as a
     * reference ({@code "$hono"}) or with nested rules is left as it is; the text around it is untouched.
     */
    private static String followingOverrides(String manifest, String packageName, String spec) {
        String result = manifest;
        for (List<String> path : List.of(List.of("overrides"), List.of("resolutions"), List.of("pnpm", "overrides"))) {
            JsonText.ObjectSpan object = JsonText.root(result);
            JsonText.Member entry = null;
            for (String key : path) {
                JsonText.Member member = object.member(key);
                if (member == null || result.charAt(member.valueStart()) != '{') {
                    object = null;
                    break;
                }
                object = JsonText.object(result, member.valueStart());
            }
            if (object != null) {
                entry = object.member(packageName);
            }
            if (entry == null || result.charAt(entry.valueStart()) != '"') {
                continue;
            }
            String current = JSON.readValue(result.substring(entry.valueStart(), entry.valueEnd()), String.class);
            if (current.startsWith("$") || current.equals(spec)) {
                continue;
            }
            result = result.substring(0, entry.valueStart()) + JsonText.string(spec) + result.substring(entry.valueEnd());
        }
        parse(result); // still JSON, or nothing is written
        return result;
    }

    private static List<Object> sections(String manifest) {
        Map<String, Object> root = parse(manifest);
        return SECTIONS.stream().map(root::get).toList();
    }

    private static Map<String, Object> parse(String manifest) {
        try {
            Map<String, Object> root = JSON.readValue(manifest, OBJECT);
            if (root == null) {
                throw new ApiExceptions.Conflict("package.json is not a JSON object");
            }
            return root;
        } catch (JacksonException notJson) {
            throw new ApiExceptions.Conflict("package.json is not valid JSON");
        }
    }
}
