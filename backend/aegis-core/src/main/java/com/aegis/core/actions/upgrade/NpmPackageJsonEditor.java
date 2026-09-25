package com.aegis.core.actions.upgrade;

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
 * a transitive package, or a tag, URL, wildcard or compound range, is refused.
 */
final class NpmPackageJsonEditor implements ManifestEditor {

    private static final List<String> SECTIONS =
            List.of("dependencies", "devDependencies", "optionalDependencies", "peerDependencies");
    private static final Pattern SIMPLE_SPEC = Pattern.compile("(\\^|~|>=|=)?v?\\d[0-9A-Za-z.+-]*");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    @Override
    public String fileName() {
        return "package.json";
    }

    @Override
    public List<String> followUps() {
        return List.of("Run `npm install` so `package-lock.json` resolves the new version.");
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
        for (String spec : specs) {
            Matcher simple = SIMPLE_SPEC.matcher(spec.trim());
            if (!simple.matches()) {
                throw new ApiExceptions.Conflict("package.json asks for " + packageName + " as \"" + spec + "\"; only a "
                        + "plain version, or one behind ^, ~, >= or =, can be upgraded automatically");
            }
            String replacement = (simple.group(1) == null ? "" : simple.group(1)) + toVersion;
            if (replacement.equals(spec)) {
                continue;
            }
            Pattern entry = Pattern.compile("(\"" + Pattern.quote(packageName) + "\"\\s*:\\s*\")" + Pattern.quote(spec) + "\"");
            result = entry.matcher(result).replaceAll("$1" + Matcher.quoteReplacement(replacement) + "\"");
            changed.add(spec);
        }
        if (changed.isEmpty()) {
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
        return result;
    }

    private static List<Object> sections(String manifest) {
        Map<String, Object> root;
        try {
            root = JSON.readValue(manifest, OBJECT);
        } catch (JacksonException notJson) {
            throw new ApiExceptions.Conflict("package.json is not valid JSON");
        }
        return SECTIONS.stream().map(root::get).toList();
    }
}
