package com.aegis.core.inventory.sbom;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aegis.core.inventory.domain.ImportScan;
import com.aegis.core.inventory.domain.ImportScan.ModuleImports;
import com.aegis.core.inventory.domain.ImportScan.Site;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a repository's checkout for the modules its code imports (E11-3): JavaScript and TypeScript {@code import},
 * {@code export … from}, {@code import()} and {@code require()}; Python {@code import} and {@code from … import}; Java
 * and Kotlin {@code import}. It reads source text and never runs the code. It skips what isn't the repository's own
 * code — {@code node_modules}, virtual environments, build output, minified bundles — symbolic links (on disk, or
 * recorded as links in the repository), and files whose names look like they hold secrets; it stops after
 * {@value #MAX_FILES} files or {@value #MAX_TOTAL_BYTES} bytes, skips a file over {@value #MAX_FILE_BYTES} bytes, and
 * says so ({@link ImportScan#limit()}). Relative imports, the language's own standard library, and anything it can't
 * read as an import are left out.
 */
final class ImportScanner {

    private static final Logger log = LoggerFactory.getLogger(ImportScanner.class);

    static final int MAX_FILES = 20_000;
    static final long MAX_FILE_BYTES = 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    static final int MAX_MODULES = 5_000;

    static final Map<String, String> LANGUAGE_BY_EXTENSION = Map.ofEntries(
            Map.entry("js", "npm"), Map.entry("jsx", "npm"), Map.entry("mjs", "npm"), Map.entry("cjs", "npm"),
            Map.entry("ts", "npm"), Map.entry("tsx", "npm"), Map.entry("mts", "npm"), Map.entry("cts", "npm"),
            Map.entry("vue", "npm"), Map.entry("svelte", "npm"),
            Map.entry("py", "pypi"),
            Map.entry("java", "maven"), Map.entry("kt", "maven"));

    /** Folders that hold someone else's code or what a build made of the repository's, never what it imports. */
    static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "node_modules", "bower_components", "jspm_packages",
            "vendor", ".venv", "venv", "site-packages", "__pycache__", ".tox", ".nox", ".mypy_cache", ".pytest_cache",
            "dist", "build", "target", "out", ".next", ".nuxt", ".svelte-kit", ".output", ".turbo", ".parcel-cache",
            "coverage", ".gradle", ".idea", ".vscode", ".yarn", ".pnpm-store");

    /** Words in a file name that mark it as likely to hold a secret; it is not read. */
    private static final List<String> SECRET_WORDS = List.of("secret", "credential", "password", "passwd",
            "private_key", "private-key", "privatekey", "apikey", "api_key", "api-key", ".env");

    // JavaScript and TypeScript: from-clauses (import … from, export … from), side-effect imports, import(), require().
    private static final Pattern JS_FROM = Pattern.compile("\\b(?:import|export)\\b[^;'\"`]*?\\bfrom\\s*(['\"])([^'\"\\n]+)\\1");
    private static final Pattern JS_BARE = Pattern.compile("\\bimport\\s*(['\"])([^'\"\\n]+)\\1");
    private static final Pattern JS_DYNAMIC = Pattern.compile("\\b(?:import|require)\\s*\\(\\s*(['\"])([^'\"\\n]+)\\1\\s*\\)");
    private static final Pattern NPM_NAME = Pattern.compile("(?:@[A-Za-z0-9][A-Za-z0-9._~-]*/)?[A-Za-z0-9][A-Za-z0-9._~-]*");
    private static final Set<String> NODE_BUILTINS = Set.of("assert", "async_hooks", "buffer", "child_process", "cluster",
            "console", "constants", "crypto", "dgram", "diagnostics_channel", "dns", "domain", "events", "fs", "http",
            "http2", "https", "inspector", "module", "net", "os", "path", "perf_hooks", "process", "punycode",
            "querystring", "readline", "repl", "stream", "string_decoder", "sys", "timers", "tls", "trace_events", "tty",
            "url", "util", "v8", "vm", "wasi", "worker_threads", "zlib");

    // Python: import a.b [as c], d; from a.b import c.
    private static final Pattern PY_IMPORT = Pattern.compile("^\\s*import\\s+(.+)$");
    private static final Pattern PY_FROM = Pattern.compile("^\\s*from\\s+([A-Za-z_][\\w.]*)\\s+import\\b");
    private static final Pattern PY_MODULE = Pattern.compile("[A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*");
    private static final Set<String> PYTHON_STANDARD_LIBRARY = Set.of("__future__", "abc", "argparse", "array", "ast",
            "asyncio", "atexit", "base64", "binascii", "bisect", "builtins", "bz2", "calendar", "cmath", "codecs",
            "collections", "concurrent", "configparser", "contextlib", "contextvars", "copy", "csv", "ctypes",
            "dataclasses", "datetime", "decimal", "difflib", "dis", "email", "enum", "errno", "faulthandler", "fcntl",
            "filecmp", "fnmatch", "fractions", "ftplib", "functools", "gc", "getpass", "gettext", "glob", "graphlib",
            "gzip", "hashlib", "heapq", "hmac", "html", "http", "imaplib", "importlib", "inspect", "io", "ipaddress",
            "itertools", "json", "keyword", "linecache", "locale", "logging", "lzma", "math", "mimetypes", "mmap",
            "multiprocessing", "numbers", "operator", "os", "pathlib", "pdb", "pickle", "pkgutil", "platform",
            "plistlib", "pprint", "profile", "pstats", "queue", "random", "re", "reprlib", "resource", "sched",
            "secrets", "select", "selectors", "shelve", "shlex", "shutil", "signal", "site", "smtplib", "socket",
            "socketserver", "sqlite3", "ssl", "stat", "statistics", "string", "struct", "subprocess", "sys",
            "sysconfig", "tarfile", "tempfile", "textwrap", "threading", "time", "timeit", "tkinter", "token",
            "tokenize", "tomllib", "trace", "traceback", "tracemalloc", "types", "typing", "unicodedata", "unittest",
            "urllib", "uuid", "venv", "warnings", "wave", "weakref", "webbrowser", "wsgiref", "xml", "xmlrpc",
            "zipfile", "zipimport", "zlib", "zoneinfo");

    // Java and Kotlin: import [static] a.b.C[.*] [as D][;].
    private static final Pattern JVM_IMPORT = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)(\\.\\*)?(?:\\s+as\\s+\\w+)?\\s*;?\\s*(?://.*)?$");
    private static final List<String> JVM_PLATFORM = List.of("java.", "jdk.", "sun.", "com.sun.", "kotlin.");

    private ImportScanner() {
    }

    /**
     * Every module the checkout's code imports. {@code links} are the repository-relative paths the repository records
     * as symbolic links (checked out as plain files, which hold only the link's target); {@code revision} is the commit
     * checked out, or null.
     */
    static ImportScan scan(Path checkout, Set<String> links, String revision) {
        List<Path> sources = new ArrayList<>();
        try {
            Files.walkFileTree(checkout, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    if (dir.equals(checkout)) {
                        return FileVisitResult.CONTINUE;
                    }
                    return attributes.isSymbolicLink() || SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                            || links.contains(relative(checkout, dir))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && !attributes.isSymbolicLink() && language(file) != null
                            && !links.contains(relative(checkout, file)) && !secretLooking(file) && !generated(file)) {
                        sources.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException unreadable) {
            log.warn("import scan: checkout not walked: {}", unreadable.getClass().getSimpleName());
        }
        sources.sort(null);
        Set<String> ownPython = ownPythonModules(checkout, sources);

        Map<String, Accumulator> modules = new LinkedHashMap<>();
        Set<String> languages = new TreeSet<>();
        List<String> limits = new ArrayList<>();
        int filesRead = 0;
        long bytesRead = 0;
        int oversized = 0;
        boolean modulesCapped = false;
        for (Path file : sources) {
            if (filesRead >= MAX_FILES) {
                limits.add("it stopped after reading " + MAX_FILES + " of " + sources.size() + " source files");
                break;
            }
            long size;
            try {
                size = Files.size(file);
            } catch (IOException unreadable) {
                continue;
            }
            if (size > MAX_FILE_BYTES) {
                oversized++;
                continue;
            }
            if (bytesRead + size > MAX_TOTAL_BYTES) {
                limits.add("it stopped after reading " + (MAX_TOTAL_BYTES / (1024 * 1024)) + " MB of source");
                break;
            }
            String text = read(file);
            if (text == null) {
                continue;
            }
            filesRead++;
            bytesRead += size;
            String language = language(file);
            languages.add(language);
            String path = relative(checkout, file);
            for (Found found : imports(language, text, ownPython)) {
                Accumulator accumulator = modules.get(language + "\0" + found.module());
                if (accumulator == null) {
                    if (modules.size() >= MAX_MODULES) {
                        modulesCapped = true;
                        continue;
                    }
                    accumulator = new Accumulator(language, found.module());
                    modules.put(language + "\0" + found.module(), accumulator);
                }
                accumulator.add(new Site(path, found.line()));
            }
        }
        if (oversized > 0) {
            limits.add(oversized + (oversized == 1 ? " source file was" : " source files were") + " larger than "
                    + (MAX_FILE_BYTES / 1024) + " KB and not read");
        }
        if (modulesCapped) {
            limits.add("only the first " + MAX_MODULES + " modules imported were kept");
        }
        String limit = limits.isEmpty() ? null : capitalized(String.join("; ", limits));
        return new ImportScan(revision, List.copyOf(languages), filesRead, limit,
                modules.values().stream().map(Accumulator::toImports).toList());
    }

    /**
     * The modules one file imports, in its language, each with the line it is imported on. {@code ownPython}: the
     * top-level modules the repository's own Python code defines ({@link #ownPythonModules}).
     */
    static List<Found> imports(String language, String text, Set<String> ownPython) {
        return switch (language) {
            case "npm" -> javascript(text);
            case "pypi" -> python(text, ownPython);
            case "maven" -> jvm(text);
            default -> List.of();
        };
    }

    /** One import a file makes: the module, and the 1-based line. */
    record Found(String module, int line) {
    }

    private static List<Found> javascript(String text) {
        String code = withoutCComments(text);
        int[] lineStarts = lineStarts(code);
        List<Found> found = new ArrayList<>();
        for (Pattern pattern : List.of(JS_FROM, JS_BARE, JS_DYNAMIC)) {
            Matcher matcher = pattern.matcher(code);
            while (matcher.find()) {
                int line = lineOf(lineStarts, matcher.start(2));
                String module = npmPackage(matcher.group(2).strip());
                if (module != null) {
                    found.add(new Found(module, line));
                }
            }
        }
        found.sort((a, b) -> Integer.compare(a.line(), b.line()));
        return found;
    }

    /**
     * The npm package a specifier names: {@code @scope/name} or {@code name}, without a subpath; null for a relative
     * path, a URL, a built-in Node module, a path alias ({@code @/components}, {@code ~/lib}) or a subpath import.
     */
    static String npmPackage(String specifier) {
        if (specifier.isEmpty() || specifier.startsWith(".") || specifier.startsWith("/") || specifier.startsWith("#")
                || specifier.startsWith("~") || specifier.contains(":") || specifier.contains("\\")) {
            return null;
        }
        String[] parts = specifier.split("/");
        String name = specifier.startsWith("@") ? (parts.length >= 2 ? parts[0] + "/" + parts[1] : null) : parts[0];
        if (name == null || !NPM_NAME.matcher(name).matches() || NODE_BUILTINS.contains(name)) {
            return null;
        }
        return name;
    }

    private static List<Found> python(String text, Set<String> ownPython) {
        List<Found> found = new ArrayList<>();
        String[] lines = withoutPythonComments(text).replace("\r", "").split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            int number = index + 1;
            // A statement runs on past a line ending in a backslash, and "from x import (a," to its closing parenthesis.
            String statement = lines[index];
            while (index + 1 < lines.length && (statement.stripTrailing().endsWith("\\") || unclosedFrom(statement))) {
                String head = statement.stripTrailing();
                statement = (head.endsWith("\\") ? head.substring(0, head.length() - 1) : head) + " " + lines[++index];
            }
            Matcher from = PY_FROM.matcher(statement);
            if (from.find()) {
                String module = from.group(1);
                List<String> modules = ownPython.contains(module.split("\\.")[0]) ? List.of(module)
                        : fromImports(module, statement.substring(from.end()));
                for (String imported : modules) {
                    addPython(found, imported, number);
                }
                continue;
            }
            Matcher plain = PY_IMPORT.matcher(statement);
            if (plain.matches()) {
                for (String part : plain.group(1).split(",")) {
                    String module = part.strip().split("\\s+as\\s+")[0].strip();
                    addPython(found, module, number);
                }
            }
        }
        return found;
    }

    /** A {@code from … import (} whose parenthesis closes on a later line. */
    private static boolean unclosedFrom(String statement) {
        return statement.indexOf('(') >= 0 && statement.indexOf(')') < 0 && PY_FROM.matcher(statement).find();
    }

    /**
     * The modules {@code from module import names} imports. A name may be a submodule ({@code from google.cloud import
     * storage} imports {@code google.cloud.storage}), and submodules are lowercase by convention, so a lowercase name is
     * recorded below the module — a function too, which is harmless: it names no package. A class or constant (CapWords,
     * UPPER), or {@code *}, records the module itself. From the repository's own packages, whose names are its own
     * functions and modules and never a dependency, only the module is recorded ({@link #python}), so a large codebase's
     * imports of its own functions don't fill the scan's {@value #MAX_MODULES} modules.
     */
    static List<String> fromImports(String module, String names) {
        List<String> modules = new ArrayList<>();
        boolean itself = false;
        for (String part : names.replace("(", " ").replace(")", " ").split(",")) {
            String name = part.strip().split("\\s+as\\s+")[0].strip();
            if (name.isEmpty()) {
                continue;
            }
            if (name.matches("[a-z_][a-z0-9_]*")) {
                modules.add(module + "." + name);
            } else {
                itself = true;
            }
        }
        if (itself || modules.isEmpty()) {
            modules.addFirst(module);
        }
        return modules;
    }

    private static void addPython(List<Found> found, String module, int line) {
        if (!PY_MODULE.matcher(module).matches()) {
            return; // relative (from . import x), or not an import at all
        }
        if (!PYTHON_STANDARD_LIBRARY.contains(module.split("\\.")[0])) {
            found.add(new Found(module, line));
        }
    }

    private static List<Found> jvm(String text) {
        List<Found> found = new ArrayList<>();
        String[] lines = withoutCComments(text).split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            Matcher matcher = JVM_IMPORT.matcher(lines[index].replace("\r", ""));
            if (!matcher.matches()) {
                continue;
            }
            List<String> segments = new ArrayList<>(Arrays.asList(matcher.group(2).split("\\.")));
            boolean wildcard = matcher.group(3) != null;
            if (!wildcard || matcher.group(1) != null) {
                segments.removeLast(); // the class, or for a static import the member
            }
            // What is left of an inner class or a static import's class is not part of the package.
            while (!segments.isEmpty() && Character.isUpperCase(segments.getLast().charAt(0))) {
                segments.removeLast();
            }
            if (segments.isEmpty()) {
                continue;
            }
            String packageName = String.join(".", segments);
            if (JVM_PLATFORM.stream().noneMatch(prefix -> (packageName + ".").startsWith(prefix))) {
                found.add(new Found(packageName, index + 1));
            }
        }
        return found;
    }

    /**
     * The top-level modules the repository's own Python code defines: each file's outermost package (the folders above
     * it holding an {@code __init__.py}), or the file itself where its folder holds none.
     */
    static Set<String> ownPythonModules(Path checkout, List<Path> sources) {
        Set<Path> packages = new HashSet<>();
        for (Path file : sources) {
            if (file.getFileName().toString().equals("__init__.py")) {
                packages.add(file.getParent());
            }
        }
        Set<String> own = new HashSet<>();
        for (Path file : sources) {
            String name = file.getFileName().toString();
            if (!"pypi".equals(language(file))) {
                continue;
            }
            String top = name.substring(0, name.lastIndexOf('.'));
            for (Path dir = file.getParent(); dir != null && !dir.equals(checkout) && packages.contains(dir);
                    dir = dir.getParent()) {
                top = dir.getFileName().toString();
            }
            own.add(top);
        }
        return own;
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    static String language(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : LANGUAGE_BY_EXTENSION.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static boolean secretLooking(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return SECRET_WORDS.stream().anyMatch(name::contains);
    }

    /** A minified or bundled file: what a build made, never the code's own imports. */
    static boolean generated(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.contains(".min.") || name.contains(".bundle.") || name.endsWith(".chunk.js");
    }

    /** The file's text, or null when it isn't UTF-8 text. */
    static String read(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notText) {
            return null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    static String relative(Path checkout, Path file) {
        return checkout.relativize(file).toString().replace('\\', '/');
    }

    /**
     * JavaScript, TypeScript, Java or Kotlin with every comment — a line comment to the end of its line, a block comment
     * across lines — blanked to spaces, newlines kept, so an import commented out is never read and every offset and
     * line stays where it was. String and template literals (and Java's text blocks) are left as they are: a {@code //}
     * in {@code "https://…"} starts no comment. A backslash outside one escapes the next character (a regular expression's
     * {@code \/}, so {@code /\/*$/} opens no comment); a literal still open at a line's end is closed there; and a block
     * comment that never closes is no comment at all — each a misreading of something else, which blanks nothing.
     */
    static String withoutCComments(String text) {
        char[] code = text.toCharArray();
        int at = 0;
        while (at < code.length) {
            char c = code[at];
            char next = at + 1 < code.length ? code[at + 1] : '\0';
            if (c == '/' && next == '/') {
                while (at < code.length && code[at] != '\n') {
                    code[at++] = ' ';
                }
            } else if (c == '/' && next == '*') {
                int end = text.indexOf("*/", at + 2);
                if (end < 0) {
                    at += 2;
                } else {
                    blank(code, at, end + 2);
                    at = end + 2;
                }
            } else if (c == '\\') {
                at += 2;
            } else if (c == '"' && next == '"' && at + 2 < code.length && code[at + 2] == '"') {
                int end = text.indexOf("\"\"\"", at + 3);
                at = end < 0 ? code.length : end + 3;
            } else if (c == '"' || c == '\'' || c == '`') {
                at = endOfLiteral(code, at, c == '`');
            } else {
                at++;
            }
        }
        return new String(code);
    }

    /**
     * Python with every {@code #} comment and every triple-quoted string (a docstring is text, not code) blanked to
     * spaces, newlines kept. Other strings are left as they are: a {@code #} in {@code "a#b"} starts no comment. A
     * triple-quoted string that never closes is left as it is.
     */
    static String withoutPythonComments(String text) {
        char[] code = text.toCharArray();
        int at = 0;
        while (at < code.length) {
            char c = code[at];
            if (c == '#') {
                while (at < code.length && code[at] != '\n') {
                    code[at++] = ' ';
                }
            } else if ((c == '"' || c == '\'') && at + 2 < code.length && code[at + 1] == c && code[at + 2] == c) {
                int end = text.indexOf(String.valueOf(c).repeat(3), at + 3);
                if (end < 0) {
                    at += 3;
                } else {
                    blank(code, at, end + 3);
                    at = end + 3;
                }
            } else if (c == '"' || c == '\'') {
                at = endOfLiteral(code, at, false);
            } else {
                at++;
            }
        }
        return new String(code);
    }

    /** Where the literal opening at {@code start} ends: after its closing quote, or at the line's end if none. */
    private static int endOfLiteral(char[] code, int start, boolean multiline) {
        char quote = code[start];
        int at = start + 1;
        while (at < code.length) {
            char c = code[at];
            if (c == '\\') {
                at += 2;
            } else if (c == quote) {
                return at + 1;
            } else if (c == '\n' && !multiline) {
                return at;
            } else {
                at++;
            }
        }
        return code.length;
    }

    /** Spaces in place of {@code [from, to)}, keeping its newlines. */
    private static void blank(char[] code, int from, int to) {
        for (int at = from; at < to; at++) {
            if (code[at] != '\n' && code[at] != '\r') {
                code[at] = ' ';
            }
        }
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>(List.of(0));
        for (int at = 0; at < text.length(); at++) {
            if (text.charAt(at) == '\n') {
                starts.add(at + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The 1-based line holding {@code offset}. */
    private static int lineOf(int[] lineStarts, int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);
        return (index >= 0 ? index : -index - 2) + 1;
    }

    private static String capitalized(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** One module's imports as they are found: every one counted, the first {@link ImportScan#MAX_SITES} kept. */
    private static final class Accumulator {
        private final String language;
        private final String module;
        private final List<Site> sites = new ArrayList<>();
        private int count;

        Accumulator(String language, String module) {
            this.language = language;
            this.module = module;
        }

        void add(Site site) {
            if (!sites.isEmpty() && sites.getLast().equals(site)) {
                return; // the same line matched twice (an import and a re-export on one line)
            }
            count++;
            if (sites.size() < ImportScan.MAX_SITES) {
                sites.add(site);
            }
        }

        ModuleImports toImports() {
            return new ModuleImports(language, module, count, sites);
        }
    }
}
