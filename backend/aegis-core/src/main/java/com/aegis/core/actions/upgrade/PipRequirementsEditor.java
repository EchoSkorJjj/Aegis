package com.aegis.core.actions.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aegis.core.matching.UpgradeTargets;
import com.aegis.core.platform.common.ApiExceptions;

/**
 * Moves an exact {@code ==} pin in a {@code requirements.txt} to the fixed version, keeping extras,
 * markers and comments ({@code requests[socks]==2.25.1 ; python_version>"3"}). A requirement with no version, or
 * only a lower bound ({@code requests}, {@code requests>=2.20}), gets the fixed version as its lower bound
 * ({@code requests>=2.31.0}). Names compare as PEP 503 says ({@code Foo_Bar} is {@code foo-bar}).
 *
 * <p>A package the file doesn't list — another one pulls it in — gets a requirement of its own at the fixed version
 * ({@link #override}), which pip resolves together with the rest, so what pulls it in gets that version too: pinned
 * with {@code ==} when the file pins everything else that way, else as a lower bound. Any other range (an upper bound,
 * {@code ~=}, an exclusion), or a file that pins hashes, is refused.
 */
final class PipRequirementsEditor implements ManifestEditor {

    private static final Pattern PIN = Pattern.compile(
            "^(\\s*)([A-Za-z0-9][A-Za-z0-9._-]*)(\\s*\\[[^\\]]*\\])?(\\s*==\\s*)([^\\s;#,]+)(.*)$");
    /** A requirement with no version, or a single lower bound, then any markers or comment. */
    private static final Pattern FLOOR = Pattern.compile(
            "^(\\s*)([A-Za-z0-9][A-Za-z0-9._-]*)(\\s*\\[[^\\]]*\\])?(?:\\s*(>=|>)\\s*([^\\s;#,]+))?(\\s*(?:;.*|#.*)?)$");
    private static final Pattern NAME = Pattern.compile("^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)");

    @Override
    public String fileName() {
        return "requirements.txt";
    }

    @Override
    public String upgrade(String manifest, String packageName, String fromVersion, String toVersion) {
        if (manifest.contains("--hash")) {
            throw hashes();
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
                Matcher floor = FLOOR.matcher(content);
                if (floor.matches() && normalize(floor.group(2)).equals(key)) {
                    String bound = floor.group(5);
                    boolean atLeast = bound != null && (bound.equals(toVersion)
                            || !UpgradeTargets.higher("pypi", toVersion, bound).equals(toVersion));
                    if (atLeast) {
                        throw new ApiExceptions.Conflict("requirements.txt already asks for " + packageName + " "
                                + floor.group(4) + bound + ", which excludes " + fromVersion
                                + "; only the installed version is stale, so reinstall instead");
                    }
                    result.append(floor.group(1)).append(floor.group(2))
                            .append(floor.group(3) == null ? "" : floor.group(3))
                            .append(">=").append(toVersion).append(floor.group(6)).append(ending);
                    updated++;
                    continue;
                }
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
                ? "requirements.txt constrains " + packageName + " with a range Aegis doesn't rewrite (an upper bound, "
                        + "~= or an exclusion); raise it to " + toVersion + " by hand"
                : packageName + " is not listed in requirements.txt, so there is no requirement to raise; re-import "
                        + "the service's SBOM");
    }

    @Override
    public String override(String manifest, String packageName, String fromVersion, String toVersion, String via) {
        if (manifest.contains("--hash")) {
            throw hashes();
        }
        String key = normalize(packageName);
        boolean everyPinned = true;
        boolean any = false;
        for (String line : manifest.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            Matcher name = NAME.matcher(line);
            if (name.find() && normalize(name.group(1)).equals(key)) {
                return upgrade(manifest, packageName, fromVersion, toVersion); // listed after all: raise it there
            }
            any = true;
            everyPinned &= PIN.matcher(line).matches();
        }
        String newline = manifest.contains("\r\n") ? "\r\n" : "\n";
        String through = via == null ? "" : "pulled in by " + via.replaceAll("[^A-Za-z0-9._, -]", "") + "; ";
        String requirement = packageName + (any && everyPinned ? "==" : ">=") + toVersion
                + "  # " + through + "raised by Aegis to a fixed version";
        return manifest + (manifest.isEmpty() || manifest.endsWith("\n") ? "" : newline) + requirement + newline;
    }

    private static ApiExceptions.Conflict hashes() {
        return new ApiExceptions.Conflict("requirements.txt pins hashes; regenerate it (for example with "
                + "pip-compile) instead of editing the version");
    }

    /** PEP 503: case-insensitive, with runs of {@code - _ .} equivalent. */
    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }
}
