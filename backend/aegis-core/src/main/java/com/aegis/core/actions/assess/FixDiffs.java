package com.aegis.core.actions.assess;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a fix commit changed in a package's own code, in a few lines for the AI (E11-5): each changed source file, with
 * the functions or classes the host's diff names in its hunk headers — or, where it names none (GitHub names none for
 * JavaScript), its first few changed lines. Tests, docs, release notes and lockfiles are left out.
 */
final class FixDiffs {

    private static final int HEADERS_PER_FILE = 4;
    private static final int LINES_PER_FILE = 3;
    private static final int MAX_LINE = 120;
    private static final Pattern FILE = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern HUNK = Pattern.compile("^@@ [^@]* @@ ?(.*)$");
    private static final Set<String> SOURCE = Set.of("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts", "vue",
            "svelte", "py", "java", "kt", "scala", "groovy", "go", "rs", "rb", "php", "cs", "c", "h", "cc", "cpp",
            "hpp", "swift", "m", "ex", "exs", "erl", "clj", "dart", "lua", "pl");
    private static final Pattern NOT_THE_PACKAGE = Pattern.compile(
            "(^|/)(tests?|__tests__|spec|specs|docs?|examples?|benchmarks?|fixtures?|release-notes)/"
                    + "|(^|/)test_[^/]*$|[._-](test|spec)s?\\.[a-z]+$|Tests?\\.(java|kt|scala)$");

    private FixDiffs() {
    }

    /** The summary of {@code diff}, one line per changed source file, stopping between files at {@code maxChars}. */
    static String summary(String diff, int maxChars) {
        return joined(files(diff).stream().map(Changed::line).filter(java.util.Objects::nonNull).toList(), maxChars);
    }

    /** {@code lines} one per line, as many as fit in {@code maxChars} whole. */
    static String joined(Iterable<String> lines, int maxChars) {
        List<String> kept = new ArrayList<>();
        int length = 0;
        for (String line : lines) {
            int added = line.length() + (kept.isEmpty() ? 0 : 1);
            if (length + added > maxChars) {
                break;
            }
            kept.add(line);
            length += added;
        }
        return String.join("\n", kept);
    }

    private static List<Changed> files(String diff) {
        List<Changed> files = new ArrayList<>();
        Changed current = null;
        for (String line : diff.replace("\r", "").split("\n", -1)) {
            Matcher file = FILE.matcher(line);
            if (file.matches()) {
                current = new Changed(file.group(2));
                files.add(current);
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher hunk = HUNK.matcher(line);
            if (hunk.matches()) {
                String header = hunk.group(1).strip();
                if (!header.isEmpty()) {
                    current.headers.add(cut(header));
                }
            } else if ((line.startsWith("+") || line.startsWith("-")) && !line.startsWith("+++")
                    && !line.startsWith("---")) {
                String changed = line.substring(1).strip();
                if (!changed.isEmpty() && current.lines.size() < LINES_PER_FILE) {
                    current.lines.add(cut(changed));
                }
            }
        }
        return files;
    }

    private static String cut(String text) {
        return text.length() <= MAX_LINE ? text : text.substring(0, MAX_LINE - 1) + "…";
    }

    /** One file of the diff: the hunk headers it names and its first changed lines. */
    private static final class Changed {
        private final String path;
        private final Set<String> headers = new LinkedHashSet<>();
        private final List<String> lines = new ArrayList<>();

        Changed(String path) {
            this.path = path;
        }

        /** Its line of the summary, or null for a file that isn't the package's own source. */
        String line() {
            int dot = path.lastIndexOf('.');
            String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!SOURCE.contains(extension) || NOT_THE_PACKAGE.matcher(path).find()) {
                return null;
            }
            if (!headers.isEmpty()) {
                return path + ": changed in " + String.join("; ", headers.stream().limit(HEADERS_PER_FILE).toList());
            }
            return lines.isEmpty() ? null : path + ": lines changed: " + String.join(" | ", lines);
        }
    }
}
