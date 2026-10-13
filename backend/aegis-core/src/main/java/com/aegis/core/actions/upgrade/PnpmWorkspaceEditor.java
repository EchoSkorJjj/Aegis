package com.aegis.core.actions.upgrade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aegis.core.platform.common.ApiExceptions;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Overrides a package a pnpm project doesn't declare in pnpm's own settings, the {@code pnpm-workspace.yaml} beside
 * the lockfile: its {@code overrides} map, which pnpm 10 reads and pnpm 11 reads alone (it ignores
 * {@code pnpm.overrides} in package.json). The override names the package alone, so every copy of it in the tree
 * moves, at {@code ^} the fixed version so later fixes in its line still arrive.
 *
 * <p>Text edits leave the rest of the file alone: an existing entry's value is replaced, a new one goes after the
 * map's last entry at its indentation, and a file without the map gets it at its end — or is created, when there is
 * none. A map written in flow style ({@code overrides: {a: 1}}), or a result that doesn't read back as exactly the
 * override asked for, is refused.
 */
final class PnpmWorkspaceEditor implements ManifestEditor {

    private static final Pattern OVERRIDES = Pattern.compile("^overrides:[ \\t]*(#.*)?$");
    private static final Pattern FLOW = Pattern.compile("^overrides:[ \\t]*\\{(.*)}[ \\t]*(#.*)?$");
    private static final Pattern ENTRY = Pattern.compile("^([ \\t]+)('(?:[^']|'')*'|\"(?:[^\"\\\\]|\\\\.)*\"|[^:#\\s][^:#]*?)[ \\t]*:(?:[ \\t].*)?$");
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern PLAIN_VALUE = Pattern.compile("[\\^~]?[0-9][0-9A-Za-z.+-]*");

    @Override
    public String fileName() {
        return "pnpm-workspace.yaml";
    }

    @Override
    public boolean createsMissing() {
        return true;
    }

    @Override
    public String upgrade(String manifest, String packageName, String fromVersion, String toVersion) {
        throw new ApiExceptions.Conflict("pnpm-workspace.yaml declares no dependency on " + packageName
                + "; its package.json does");
    }

    @Override
    public String override(String manifest, String packageName, String fromVersion, String toVersion, String via) {
        String range = NpmPackageJsonEditor.overrideRange(toVersion);
        Map<String, Object> before = read(manifest);
        Object existing = before.get("overrides");
        if (existing instanceof Map<?, ?> current && range.equals(current.get(packageName))) {
            throw new ApiExceptions.Conflict("pnpm-workspace.yaml already overrides " + packageName + " to " + range
                    + "; only the lockfile is stale, so run `pnpm install` instead");
        }
        String newline = manifest.contains("\r\n") ? "\r\n" : "\n";
        String entry = key(packageName) + ": " + value(range);
        List<String> lines = new ArrayList<>(List.of(manifest.split("\r?\n", -1)));
        int header = -1;
        for (int at = 0; at < lines.size(); at++) {
            if (FLOW.matcher(lines.get(at)).matches()) {
                Matcher flow = FLOW.matcher(lines.get(at));
                flow.matches();
                if (!flow.group(1).isBlank()) {
                    throw new ApiExceptions.Conflict("pnpm-workspace.yaml writes its overrides on one line; add "
                            + packageName + ": " + range + " there by hand");
                }
                lines.set(at, "overrides:");
                header = at;
                break;
            }
            if (OVERRIDES.matcher(lines.get(at)).matches()) {
                header = at;
                break;
            }
        }
        String result;
        if (header < 0) {
            String text = manifest;
            if (!text.isEmpty() && !text.endsWith("\n")) {
                text += newline;
            }
            result = text + "overrides:" + newline + "  " + entry + newline;
        } else {
            // The map's entries: the indented lines after its key, up to the next line at the left margin.
            int last = header;
            String indent = null;
            int found = -1;
            for (int at = header + 1; at < lines.size(); at++) {
                String line = lines.get(at);
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    continue;
                }
                if (!Character.isWhitespace(line.charAt(0))) {
                    break;
                }
                Matcher member = ENTRY.matcher(line);
                if (indent == null && member.matches()) {
                    indent = member.group(1);
                }
                if (member.matches() && member.group(1).equals(indent)) {
                    if (unquote(member.group(2).strip()).equals(packageName)) {
                        found = at;
                    }
                }
                last = at;
            }
            if (indent == null) {
                indent = "  ";
            }
            if (found >= 0) {
                lines.set(found, indent + entry);
            } else {
                lines.add(last + 1, indent + entry);
            }
            result = String.join(newline, lines);
        }
        // The text edit must read back as exactly the override asked for, and nothing else moved.
        Map<String, Object> after = read(result);
        Map<String, Object> expected = new LinkedHashMap<>(before);
        Map<String, Object> overrides = new LinkedHashMap<>();
        if (existing instanceof Map<?, ?> current) {
            current.forEach((name, version) -> overrides.put(String.valueOf(name), version));
        }
        overrides.put(packageName, range);
        expected.put("overrides", overrides);
        if (!after.equals(expected)) {
            throw new ApiExceptions.Conflict("pnpm-workspace.yaml is laid out in a way Aegis can't edit safely; add "
                    + packageName + ": " + range + " under its overrides by hand");
        }
        return result;
    }

    /**
     * An override this file already has of a package whose declaration moves elsewhere in the pull request, raised to
     * {@code ^} the fixed version — pnpm applies overrides to declared dependencies too, so one left behind would keep
     * the old version. A missing file, or one without that override, is left as it is.
     */
    @Override
    public String raiseOverride(String manifest, String packageName, String toVersion) {
        if (manifest.isBlank()) {
            return manifest;
        }
        Object overrides = read(manifest).get("overrides");
        if (!(overrides instanceof Map<?, ?> current) || !(current.get(packageName) instanceof String version)
                || version.equals(NpmPackageJsonEditor.overrideRange(toVersion))) {
            return manifest;
        }
        return override(manifest, packageName, null, toVersion, null);
    }

    /**
     * pnpm reads the override only once its lockfile follows: {@code pnpm install} in each folder. pnpm 9 and older
     * don't read pnpm-workspace.yaml's overrides at all.
     */
    @Override
    public List<String> followUps(List<String> manifestPaths) {
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
        return List.of("Run `pnpm install` in " + UpgradePullRequestText.and(directories) + " and commit the updated "
                + "`pnpm-lock.yaml` to this branch. Until then the lockfile still pins the old version, so a frozen "
                + "install refuses it and Aegis keeps reporting the vulnerability. The override is in "
                + "`pnpm-workspace.yaml`, which pnpm 10 and later read; on pnpm 9 or older, move it to `pnpm.overrides` "
                + "in " + (several ? "each" : "the") + " `package.json` there instead.");
    }

    private static Map<String, Object> read(String text) {
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            LoaderOptions options = new LoaderOptions();
            DumperOptions dumper = new DumperOptions();
            Object document = new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options,
                    new Resolver() {
                        @Override
                        protected void addImplicitResolvers() {
                            // none: every plain scalar is a string, as pnpm reads a version
                        }
                    }).load(text);
            if (document == null) {
                return Map.of();
            }
            if (!(document instanceof Map<?, ?> map)) {
                throw new ApiExceptions.Conflict("pnpm-workspace.yaml is not a map of settings");
            }
            Map<String, Object> settings = new LinkedHashMap<>();
            map.forEach((key, value) -> settings.put(String.valueOf(key), value));
            return settings;
        } catch (YAMLException unreadable) {
            throw new ApiExceptions.Conflict("pnpm-workspace.yaml is not valid YAML");
        }
    }

    /** A YAML key: plain when it can be, else single-quoted ({@code '@hono/node-server'}). */
    private static String key(String name) {
        return PLAIN_KEY.matcher(name).matches() ? name : "'" + name.replace("'", "''") + "'";
    }

    private static String value(String range) {
        return PLAIN_VALUE.matcher(range).matches() ? range : "'" + range.replace("'", "''") + "'";
    }

    private static String unquote(String key) {
        if (key.length() >= 2 && key.startsWith("'") && key.endsWith("'")) {
            return key.substring(1, key.length() - 1).replace("''", "'");
        }
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            return key.substring(1, key.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return key;
    }
}
