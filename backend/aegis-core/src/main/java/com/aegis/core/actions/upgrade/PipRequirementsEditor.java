package com.aegis.core.actions.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aegis.core.platform.common.ApiExceptions;

/**
 * Moves an exact {@code ==} pin in a {@code requirements.txt} to the fixed version, keeping extras,
 * markers and comments ({@code requests[socks]==2.25.1 ; python_version>"3"}). Names compare as PEP 503
 * says ({@code Foo_Bar} is {@code foo-bar}). A package that is not pinned with {@code ==}, not listed at
 * all (transitive), or listed in a file that pins hashes is refused.
 */
final class PipRequirementsEditor implements ManifestEditor {

    private static final Pattern PIN = Pattern.compile(
            "^(\\s*)([A-Za-z0-9][A-Za-z0-9._-]*)(\\s*\\[[^\\]]*\\])?(\\s*==\\s*)([^\\s;#,]+)(.*)$");
    private static final Pattern NAME = Pattern.compile("^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)");

    @Override
    public String fileName() {
        return "requirements.txt";
    }

    @Override
    public String upgrade(String manifest, String packageName, String fromVersion, String toVersion) {
        if (manifest.contains("--hash")) {
            throw new ApiExceptions.Conflict("requirements.txt pins hashes; regenerate it (for example with "
                    + "pip-compile) instead of editing the version");
        }
        String key = normalize(packageName);
        StringBuilder result = new StringBuilder();
        List<String> otherPins = new ArrayList<>();
        int updated = 0;
        int unpinned = 0;
        for (String line : manifest.split("(?<=\n)", -1)) {
            String ending = line.endsWith("\r\n") ? "\r\n" : line.endsWith("\n") ? "\n" : "";
            String content = line.substring(0, line.length() - ending.length());
            String trimmed = content.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                result.append(line);
                continue;
            }
            Matcher pin = PIN.matcher(content);
            if (pin.matches() && normalize(pin.group(2)).equals(key)) {
                if (pin.group(5).equals(fromVersion)) {
                    result.append(content, 0, pin.start(5)).append(toVersion).append(content.substring(pin.end(5)))
                            .append(ending);
                    updated++;
                    continue;
                }
                otherPins.add(pin.group(5));
            } else {
                Matcher name = NAME.matcher(content);
                if (name.find() && normalize(name.group(1)).equals(key)) {
                    unpinned++;
                }
            }
            result.append(line);
        }
        if (updated > 0) {
            return result.toString();
        }
        if (!otherPins.isEmpty()) {
            throw new ApiExceptions.Conflict("requirements.txt pins " + packageName + " at " + String.join(", ", otherPins)
                    + ", not " + fromVersion + " — re-import the service's SBOM");
        }
        throw new ApiExceptions.Conflict(unpinned > 0
                ? "requirements.txt does not pin " + packageName + " with ==, so the resolved version is not "
                        + "controlled there; pin it by hand"
                : packageName + " is not listed in requirements.txt: it comes in transitively, so upgrade the "
                        + "package that depends on it instead");
    }

    /** PEP 503: case-insensitive, with runs of {@code - _ .} equivalent. */
    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }
}
