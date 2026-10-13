package com.aegis.core.inventory.sbom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a repository's JavaScript lockfiles say that syft's SBOM of them loses, read from the same checkout. Syft
 * reports every package a lockfile pins but not which ones the project itself declares, so the import took any package
 * nothing depends on for a declared one; and it leaves optional dependencies out of the graph (npm and pnpm), so
 * {@code sharp}, the platform binaries and every other optional package looked declared, and their upgrades failed
 * when the pull request opened. Each lockfile here gives both: the packages each of its projects declares, in which
 * {@code package.json}, and every dependency edge, optional ones included ({@link RepositoryLayout} uses them).
 *
 * <p>npm ({@code package-lock.json}, {@code npm-shrinkwrap.json}: lockfile versions 2 and 3, workspaces included;
 * version 1 holds no project, so the {@code package.json} beside it says what is declared), pnpm
 * ({@code pnpm-lock.yaml}, versions 5 to 9, workspaces through its importers), and yarn ({@code yarn.lock}: classic,
 * which names no project either, so the {@code package.json} beside it and its workspaces declare; and berry). A
 * lockfile that can't be read is left out, and the import treats its packages as it did before.
 */
final class JsLockfiles {

    private static final Logger log = LoggerFactory.getLogger(JsLockfiles.class);

    static final Set<String> FILE_NAMES = Set.of("package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml",
            "yarn.lock");
    private static final int MAX_LOCKFILES = 100;
    static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final long MAX_PACKAGE_JSON_BYTES = 1024 * 1024;
    /** What a project declares: dev tools too, which syft reports and an upgrade edits like any other. */
    private static final List<String> DECLARING = List.of("dependencies", "devDependencies", "optionalDependencies");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    private JsLockfiles() {
    }

    /** The package manager a lockfile belongs to. */
    enum Manager { NPM, PNPM, YARN }

    /** A package at one version, as the lockfile resolves it. */
    record Pkg(String name, String version) {
    }

    /**
     * One project the lockfile installs — the repository's package, or one of its workspaces: the {@code package.json}
     * that declares its dependencies, the package it is itself when the lockfile lists it as one ({@code self}, null
     * otherwise), and the packages it declares.
     */
    record Project(String manifestPath, Pkg self, Set<Pkg> declared) {
    }

    /** {@code from} depends on {@code to}, optionally or not. */
    record Edge(Pkg from, Pkg to) {
    }

    /** One lockfile: its repository-relative path, whose it is, its projects and its dependency edges. */
    record Lockfile(String path, Manager manager, List<Project> projects, Set<Edge> edges) {

        /** The folder it sits in, as a path prefix: {@code client/}, or empty at the repository root. */
        String directory() {
            return JsLockfiles.directory(path);
        }
    }

    /** Every JavaScript lockfile in the checkout, outside {@code node_modules} and {@code .git}, in path order. */
    static List<Lockfile> read(Path checkout) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(checkout, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return !dir.equals(checkout) && (name.equals("node_modules") || name.equals(".git"))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && FILE_NAMES.contains(file.getFileName().toString())) {
                        found.add(file);
                    }
                    return found.size() >= MAX_LOCKFILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException unreadable) {
            log.warn("lockfiles not read: {}", unreadable.toString());
            return List.of();
        }
        found.sort(null);
        List<Lockfile> lockfiles = new ArrayList<>();
        for (Path file : found) {
            parse(checkout, file).ifPresent(lockfiles::add);
        }
        return lockfiles;
    }

    /** One lockfile in the checkout, or empty when it can't be read. */
    static Optional<Lockfile> parse(Path checkout, Path file) {
        String path = relative(checkout, file);
        try {
            if (Files.size(file) > MAX_BYTES) {
                log.info("lockfile {} not read: larger than {} bytes", path, MAX_BYTES);
                return Optional.empty();
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Checkout files = new Checkout(checkout);
            return Optional.of(switch (file.getFileName().toString()) {
                case "pnpm-lock.yaml" -> pnpm(path, content);
                case "yarn.lock" -> content.contains("__metadata:") ? berry(path, content) : classic(path, content, files);
                default -> npm(path, content, files);
            });
        } catch (IOException | RuntimeException unreadable) {
            log.warn("lockfile {} not read: {}", path, unreadable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // --- npm ---------------------------------------------------------------------------------------------------

    /**
     * Lockfile versions 2 and 3 list every package by where it is installed ({@code node_modules/a/node_modules/b}),
     * the project at {@code ""} and each workspace at its folder; a dependency resolves the way Node finds it, from
     * the package's own {@code node_modules} outward. Version 1 nests packages instead and names no project.
     */
    private static Lockfile npm(String path, String content, Checkout files) {
        Map<String, Object> root = JSON.readValue(content, OBJECT);
        String dir = directory(path);
        Map<String, Object> packages = map(root.get("packages"));
        if (packages.isEmpty()) {
            Map<String, Object> top = map(root.get("dependencies"));
            Set<Pkg> declared = new LinkedHashSet<>();
            declaredRanges(files.json(dir + "package.json")).forEach((name, range) -> {
                String version = string(map(top.get(name)).get("version"));
                if (plainVersion(version)) {
                    declared.add(new Pkg(name, version));
                }
            });
            return new Lockfile(path, Manager.NPM, List.of(new Project(dir + "package.json", null, declared)), Set.of());
        }
        List<Project> projects = new ArrayList<>();
        Set<Edge> edges = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : packages.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> value = map(entry.getValue());
            if (isTrue(value.get("link"))) {
                continue; // a workspace, linked into node_modules: the project entry at its folder says what it declares
            }
            if (!installed(key)) {
                Set<Pkg> declared = new LinkedHashSet<>();
                for (String section : DECLARING) {
                    for (String name : map(value.get(section)).keySet()) {
                        Pkg pkg = npmPackage(packages, locate(packages, key, name));
                        if (pkg != null) {
                            declared.add(pkg);
                        }
                    }
                }
                String name = string(value.get("name"));
                String version = string(value.get("version"));
                projects.add(new Project(dir + (key.isEmpty() ? "" : key + "/") + "package.json",
                        name != null && plainVersion(version) ? new Pkg(name, version) : null, declared));
                continue;
            }
            Pkg from = npmPackage(packages, key);
            if (from == null) {
                continue;
            }
            for (String section : List.of("dependencies", "optionalDependencies", "peerDependencies")) {
                for (String name : map(value.get(section)).keySet()) {
                    Pkg to = npmPackage(packages, locate(packages, key, name));
                    if (to != null && !to.equals(from)) {
                        edges.add(new Edge(from, to));
                    }
                }
            }
        }
        return new Lockfile(path, Manager.NPM, projects, edges);
    }

    /** A package installed under {@code node_modules}; any other key is the project or one of its workspaces. */
    private static boolean installed(String key) {
        return key.startsWith("node_modules/") || key.contains("/node_modules/");
    }

    /** Where Node finds {@code name} from the package or project at {@code from}: its own node_modules, then outward. */
    static String locate(Map<String, Object> packages, String from, String name) {
        String base = from;
        while (true) {
            String candidate = (base.isEmpty() ? "" : base + "/") + "node_modules/" + name;
            if (packages.containsKey(candidate)) {
                return candidate;
            }
            if (base.isEmpty()) {
                return null;
            }
            int nested = base.lastIndexOf("/node_modules/");
            base = nested >= 0 ? base.substring(0, nested) : "";
        }
    }

    /** The package installed at {@code key}; null for a workspace link or a version that isn't one (a tarball URL). */
    private static Pkg npmPackage(Map<String, Object> packages, String key) {
        if (key == null) {
            return null;
        }
        Map<String, Object> value = map(packages.get(key));
        if (isTrue(value.get("link"))) {
            return null;
        }
        String version = string(value.get("version"));
        String name = string(value.get("name"));
        if (name == null) {
            name = key.substring(key.lastIndexOf("node_modules/") + "node_modules/".length());
        }
        return plainVersion(version) ? new Pkg(name, version) : null;
    }

    // --- pnpm --------------------------------------------------------------------------------------------------

    /**
     * Each importer is a project (the repository's at {@code .}, a workspace at its folder; a lockfile from before
     * workspaces has its dependencies at the top level instead). Version 9 keeps each package's dependencies under
     * {@code snapshots}, earlier versions under {@code packages}; a version carries its peers in parentheses (in an
     * underscore suffix before version 6), and an alias names the package it stands for.
     */
    private static Lockfile pnpm(String path, String content) {
        Map<String, Object> doc = map(yaml(content).load(content));
        String dir = directory(path);
        int major = major(string(doc.get("lockfileVersion")));
        Map<String, Object> importers = map(doc.get("importers"));
        if (importers.isEmpty()) {
            importers = Map.of(".", doc);
        }
        List<Project> projects = new ArrayList<>();
        for (Map.Entry<String, Object> importer : importers.entrySet()) {
            String folder = folder(importer.getKey());
            if (folder == null) {
                continue;
            }
            Map<String, Object> value = map(importer.getValue());
            Set<Pkg> declared = new LinkedHashSet<>();
            for (String section : DECLARING) {
                map(value.get(section)).forEach((name, spec) -> {
                    Object version = spec instanceof Map<?, ?> specified ? specified.get("version") : spec;
                    Pkg pkg = pnpmPackage(name, string(version), major);
                    if (pkg != null) {
                        declared.add(pkg);
                    }
                });
            }
            projects.add(new Project(dir + folder + "package.json", null, declared));
        }
        Set<Edge> edges = new LinkedHashSet<>();
        map(doc.get(major >= 9 ? "snapshots" : "packages")).forEach((key, value) -> {
            Pkg from = pnpmKey(key, major);
            if (from == null) {
                return;
            }
            for (String section : List.of("dependencies", "optionalDependencies")) {
                map(map(value).get(section)).forEach((name, version) -> {
                    Pkg to = pnpmPackage(name, string(version), major);
                    if (to != null && !to.equals(from)) {
                        edges.add(new Edge(from, to));
                    }
                });
            }
        });
        return new Lockfile(path, Manager.PNPM, projects, edges);
    }

    /** {@code name} at {@code version} as a pnpm lockfile writes a dependency; null for a link, file or URL. */
    static Pkg pnpmPackage(String name, String version, int major) {
        if (name == null || version == null || version.isBlank() || version.startsWith("link:")
                || version.startsWith("file:") || version.startsWith("workspace:")) {
            return null;
        }
        String bare = withoutPeers(version, major);
        if (bare.startsWith("/")) {
            return pnpmKey(bare, major); // a dependency path: an alias, or a package from another registry
        }
        int at = bare.lastIndexOf('@');
        if (at > 0) {
            // An alias (version 9): "string-width-cjs": "string-width@4.2.3".
            String version9 = bare.substring(at + 1);
            return plainVersion(version9) ? new Pkg(bare.substring(0, at), version9) : null;
        }
        return plainVersion(bare) ? new Pkg(name, bare) : null;
    }

    /** A package's key: {@code next@16.1.6(react@19.2.3)} (9), {@code /next@16.1.6(...)} (6), {@code /next/16.1.6_react@19} (5). */
    static Pkg pnpmKey(String key, int major) {
        String bare = key.startsWith("/") ? key.substring(1) : key;
        String name;
        String version;
        if (major >= 6) {
            bare = withoutPeers(bare, major);
            int at = bare.lastIndexOf('@');
            if (at <= 0) {
                return null;
            }
            name = bare.substring(0, at);
            version = bare.substring(at + 1);
        } else {
            int slash = bare.lastIndexOf('/');
            if (slash <= 0) {
                return null;
            }
            name = bare.substring(0, slash);
            version = withoutPeers(bare.substring(slash + 1), major);
        }
        return plainVersion(version) && !name.contains(":") ? new Pkg(name, version) : null;
    }

    private static String withoutPeers(String version, int major) {
        int peers = version.indexOf(major >= 6 ? '(' : '_');
        return peers < 0 ? version : version.substring(0, peers);
    }

    // --- yarn --------------------------------------------------------------------------------------------------

    /**
     * Yarn classic: each entry is headed by the ranges it satisfies ({@code "react@19.2.3", react@^19:}), then its
     * version and the ranges it depends on. It names no project: the {@code package.json} beside it, and those of the
     * workspaces it lists, declare.
     */
    private static Lockfile classic(String path, String content, Checkout files) {
        List<ClassicEntry> entries = new ArrayList<>();
        ClassicEntry current = null;
        boolean inDependencies = false;
        for (String raw : content.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            String text = line.strip();
            if (indent == 0 && text.endsWith(":")) {
                List<String> descriptors = new ArrayList<>();
                for (String descriptor : text.substring(0, text.length() - 1).split(",")) {
                    descriptors.add(unquote(descriptor.strip()));
                }
                current = new ClassicEntry(descriptors);
                entries.add(current);
                inDependencies = false;
            } else if (current != null && indent == 2) {
                inDependencies = text.equals("dependencies:") || text.equals("optionalDependencies:");
                if (text.startsWith("version ")) {
                    current.version = unquote(text.substring("version ".length()).strip());
                }
            } else if (current != null && indent >= 4 && inDependencies) {
                String[] pair = splitPair(text);
                if (pair != null) {
                    current.dependencies.put(pair[0], pair[1]);
                }
            }
        }
        Map<String, Pkg> byDescriptor = new HashMap<>();
        for (ClassicEntry entry : entries) {
            String name = descriptorName(entry.descriptors.getFirst());
            if (name != null && plainVersion(entry.version)) {
                Pkg pkg = new Pkg(name, entry.version);
                entry.descriptors.forEach(descriptor -> byDescriptor.put(descriptor, pkg));
            }
        }
        Set<Edge> edges = new LinkedHashSet<>();
        for (ClassicEntry entry : entries) {
            Pkg from = byDescriptor.get(entry.descriptors.getFirst());
            if (from == null) {
                continue;
            }
            entry.dependencies.forEach((name, range) -> {
                Pkg to = byDescriptor.get(name + "@" + range);
                if (to != null && !to.equals(from)) {
                    edges.add(new Edge(from, to));
                }
            });
        }
        String dir = directory(path);
        List<Project> projects = new ArrayList<>();
        for (String manifest : classicManifests(dir, files)) {
            Set<Pkg> declared = new LinkedHashSet<>();
            declaredRanges(files.json(manifest)).forEach((name, range) -> {
                Pkg pkg = byDescriptor.get(name + "@" + range);
                if (pkg != null) {
                    declared.add(pkg);
                }
            });
            projects.add(new Project(manifest, null, declared));
        }
        return new Lockfile(path, Manager.YARN, projects, edges);
    }

    /** One yarn classic entry as it is read: the ranges it satisfies, its version, and what it depends on. */
    private static final class ClassicEntry {
        private final List<String> descriptors;
        private final Map<String, String> dependencies = new LinkedHashMap<>();
        private String version;

        ClassicEntry(List<String> descriptors) {
            this.descriptors = descriptors;
        }
    }

    /** The {@code package.json} beside a yarn classic lockfile, then each workspace's it lists that exists. */
    private static List<String> classicManifests(String dir, Checkout files) {
        List<String> manifests = new ArrayList<>(List.of(dir + "package.json"));
        Object workspaces = files.json(dir + "package.json").get("workspaces");
        Object patterns = workspaces instanceof Map<?, ?> nested ? nested.get("packages") : workspaces;
        if (!(patterns instanceof List<?> list)) {
            return manifests;
        }
        for (Object pattern : list) {
            String folder = pattern instanceof String text ? folder(text) : null;
            if (folder == null || folder.isEmpty()) {
                continue;
            }
            if (folder.endsWith("/*/")) {
                String parent = folder.substring(0, folder.length() - "*/".length());
                for (String child : files.directories(dir + parent)) {
                    manifests.add(dir + parent + child + "/package.json");
                }
            } else if (!folder.contains("*")) {
                manifests.add(dir + folder + "package.json");
            }
        }
        return manifests.stream().distinct().filter(files::exists).toList();
    }

    /**
     * Yarn berry: YAML whose keys are the descriptors an entry satisfies ({@code "react@npm:19.2.3, react@npm:^19"}) and
     * whose values carry the resolution; a workspace resolves to {@code name@workspace:folder} and lists what it
     * declares.
     */
    private static Lockfile berry(String path, String content) {
        Map<String, Object> doc = new LinkedHashMap<>(map(yaml(content).load(content)));
        doc.remove("__metadata");
        record Entry(Pkg pkg, String workspace, Map<String, Object> dependencies) {
        }
        List<Entry> entries = new ArrayList<>();
        Map<String, Pkg> byDescriptor = new HashMap<>();
        doc.forEach((key, value) -> {
            Map<String, Object> entry = map(value);
            String resolution = string(entry.get("resolution"));
            String version = string(entry.get("version"));
            int at = resolution == null ? -1 : resolution.indexOf('@', 1);
            if (at < 0 || !plainVersion(version)) {
                return;
            }
            String reference = resolution.substring(at + 1);
            Pkg pkg = new Pkg(resolution.substring(0, at), version);
            for (String descriptor : key.split(",")) {
                byDescriptor.put(descriptor.strip(), pkg);
            }
            entries.add(new Entry(pkg, reference.startsWith("workspace:") ? reference.substring("workspace:".length())
                    : null, map(entry.get("dependencies"))));
        });
        String dir = directory(path);
        Set<Pkg> workspaces = new LinkedHashSet<>();
        entries.stream().filter(entry -> entry.workspace() != null).forEach(entry -> workspaces.add(entry.pkg()));
        List<Project> projects = new ArrayList<>();
        Set<Edge> edges = new LinkedHashSet<>();
        for (Entry entry : entries) {
            Set<Pkg> dependencies = new LinkedHashSet<>();
            entry.dependencies().forEach((name, range) -> {
                String spec = string(range);
                Pkg to = spec == null ? null : byDescriptor.getOrDefault(name + "@" + spec,
                        byDescriptor.get(name + "@npm:" + spec));
                if (to != null && !to.equals(entry.pkg()) && !workspaces.contains(to)) {
                    dependencies.add(to);
                }
            });
            if (entry.workspace() != null) {
                String folder = folder(entry.workspace());
                if (folder != null) {
                    projects.add(new Project(dir + folder + "package.json", entry.pkg(), dependencies));
                }
            } else {
                dependencies.forEach(to -> edges.add(new Edge(entry.pkg(), to)));
            }
        }
        return new Lockfile(path, Manager.YARN, projects, edges);
    }

    /** The package a yarn descriptor names: {@code @scope/name@^1} is {@code @scope/name}; an npm alias, what it aliases. */
    private static String descriptorName(String descriptor) {
        int at = descriptor.indexOf('@', 1);
        if (at < 0) {
            return null;
        }
        String range = descriptor.substring(at + 1);
        if (range.startsWith("npm:")) {
            String aliased = range.substring("npm:".length());
            int version = aliased.lastIndexOf('@');
            if (version > 0) {
                return aliased.substring(0, version);
            }
        }
        return descriptor.substring(0, at);
    }

    /** {@code name "range"} or {@code "@scope/name" "range"}, as yarn classic writes a dependency. */
    private static String[] splitPair(String text) {
        String name;
        String rest;
        if (text.startsWith("\"")) {
            int close = text.indexOf('"', 1);
            if (close < 0) {
                return null;
            }
            name = text.substring(1, close);
            rest = text.substring(close + 1);
        } else {
            int space = text.indexOf(' ');
            if (space < 0) {
                return null;
            }
            name = text.substring(0, space);
            rest = text.substring(space);
        }
        String range = unquote(rest.strip());
        return name.isEmpty() || range.isEmpty() ? null : new String[] {name, range};
    }

    // --- shared ------------------------------------------------------------------------------------------------

    /** The ranges a package.json declares, dev and optional dependencies included, by package name. */
    private static Map<String, String> declaredRanges(Map<String, Object> packageJson) {
        Map<String, String> ranges = new LinkedHashMap<>();
        for (String section : DECLARING) {
            map(packageJson.get(section)).forEach((name, range) -> {
                if (range instanceof String text) {
                    ranges.putIfAbsent(name, text);
                }
            });
        }
        return ranges;
    }

    /** A YAML reader that keeps every scalar a string ({@code 1.10} stays "1.10") and builds no objects. */
    private static Yaml yaml(String content) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit((int) Math.min(Integer.MAX_VALUE, Math.max(3 * 1024 * 1024L, content.length() + 1L)));
        options.setAllowDuplicateKeys(true);
        DumperOptions dumper = new DumperOptions();
        return new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options, new Resolver() {
            @Override
            protected void addImplicitResolvers() {
                // none: every plain scalar is a string
            }
        });
    }

    /** {@code client/} for {@code client/yarn.lock}; empty at the repository root. */
    static String directory(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    /** A workspace folder as a path prefix ({@code packages/web/}; empty for {@code .}), or null if it leaves the repository. */
    private static String folder(String path) {
        String folder = path.strip().replace('\\', '/');
        while (folder.startsWith("./")) {
            folder = folder.substring(2);
        }
        if (folder.equals(".") || folder.isEmpty()) {
            return "";
        }
        if (folder.startsWith("/") || Stream.of(folder.split("/")).anyMatch(segment -> segment.equals(".."))) {
            return null;
        }
        return folder.endsWith("/") ? folder : folder + "/";
    }

    private static String relative(Path checkout, Path file) {
        return checkout.relativize(file).toString().replace('\\', '/');
    }

    /** A version a registry published, not a link, file, URL or git reference. */
    static boolean plainVersion(String version) {
        return version != null && !version.isBlank() && version.chars().noneMatch(c -> c == ':' || c == '/'
                || c == '#' || Character.isWhitespace(c));
    }

    private static int major(String lockfileVersion) {
        if (lockfileVersion == null) {
            return 9;
        }
        String digits = lockfileVersion.strip().replaceAll("^'|'$", "").split("\\.")[0];
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException unknown) {
            return 9;
        }
    }

    private static String unquote(String text) {
        return text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"") ? text.substring(1, text.length() - 1)
                : text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String string(Object value) {
        return value instanceof String text ? text : value == null ? null : value.toString();
    }

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(value);
    }

    /** Files beside a lockfile in the checkout: a {@code package.json} and workspace folders, never outside it. */
    private record Checkout(Path root) {

        Map<String, Object> json(String relativePath) {
            Path file = inside(relativePath);
            try {
                if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(file) > MAX_PACKAGE_JSON_BYTES) {
                    return Map.of();
                }
                return JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), OBJECT);
            } catch (IOException | RuntimeException unreadable) {
                return Map.of();
            }
        }

        boolean exists(String relativePath) {
            Path file = inside(relativePath);
            return file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
        }

        List<String> directories(String relativeDir) {
            Path dir = inside(relativeDir.isEmpty() ? "." : relativeDir);
            if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            try (Stream<Path> children = Files.list(dir)) {
                return children.filter(child -> Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
                        .map(child -> child.getFileName().toString())
                        .filter(name -> !name.equals("node_modules") && !name.startsWith("."))
                        .sorted()
                        .toList();
            } catch (IOException unreadable) {
                return List.of();
            }
        }

        private Path inside(String relativePath) {
            Path resolved = root.resolve(relativePath).normalize();
            return resolved.startsWith(root.normalize()) ? resolved : null;
        }
    }
}
