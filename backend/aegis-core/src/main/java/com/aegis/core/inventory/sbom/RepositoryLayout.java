package com.aegis.core.inventory.sbom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aegis.core.inventory.sbom.CycloneDxBom.Component;
import com.aegis.core.inventory.sbom.CycloneDxBom.Dependency;
import com.aegis.core.inventory.sbom.CycloneDxBom.Metadata;
import com.aegis.core.inventory.sbom.CycloneDxBom.Property;

/**
 * A scanned repository's layout, read from syft's SBOM. Syft scans a directory, so it reports the
 * repository's own packages as components — a Maven module (found at its own {@code pom.xml}) or an npm
 * {@code package-lock.json}'s root project — with the libraries they declare below them. Taken literally
 * that makes every library a service uses look transitive, so none could ever be upgraded, and it gives two
 * repositories' private {@code client@0.0.0} the same coordinates.
 *
 * <p>{@link #of} turns them back into what they are, the repository itself: a project package leaves the
 * components, what it declares becomes a direct dependency of the service, and each direct dependency
 * remembers every manifest that declares it ({@code client/package.json}, {@code service/pom.xml}) for an
 * upgrade to edit — the same library version in two manifests is one component, which syft reports once
 * with a location for each. A project package is a dependency-graph root that depends on something, found by syft's
 * Maven cataloger at a {@code pom.xml} or its npm lockfile cataloger at a {@code package-lock.json} /
 * {@code npm-shrinkwrap.json}.
 *
 * <p>Syft's graph of a JavaScript lockfile is not enough on its own: it doesn't say which packages the project
 * declares, and it drops optional dependencies. So the lockfiles themselves ({@link JsLockfiles}) decide, for every
 * package syft found in one: a package is direct when one of the lockfile's projects declares it — recorded with that
 * project's {@code package.json} and the lockfile — and the lockfile's own edges, optional ones included, hang the
 * rest below what pulls them in. A package such a lockfile resolves that nothing declares and nothing depends on is
 * kept, with its lockfile and no manifest: an upgrade overrides it rather than editing a declaration that isn't there.
 * Without a lockfile Aegis could read, a package nothing depends on counts as declared, as syft's graph suggests.
 *
 * @param bom            the SBOM with the project packages removed and their dependencies hung on the root
 * @param manifestsByRef the files declaring each direct dependency, by bom-ref, where they are known: the manifest
 *                       an upgrade edits (an npm lockfile's {@code package.json}, a {@code pom.xml}), or the file
 *                       itself when Aegis doesn't edit it (a Gradle lockfile, {@code poetry.lock}, a jar)
 * @param lockfilesByRef the JavaScript lockfiles resolving each direct dependency, by bom-ref, where Aegis read them:
 *                       an override of a package it pulls in belongs to that lockfile's project, in its package
 *                       manager's syntax
 * @param projects       the project packages removed — a re-import unlinks them from the service
 */
record RepositoryLayout(CycloneDxBom bom, Map<String, List<String>> manifestsByRef,
                        Map<String, List<String>> lockfilesByRef, List<Component> projects) {

    private static final String LOCATION = "syft:location:0:path";
    /** Syft numbers a package's locations, one for every file it was found in. */
    private static final Pattern LOCATION_PATH = Pattern.compile("syft:location:(\\d+):path");
    private static final String FOUND_BY = "syft:package:foundBy";
    /** The repository itself, when the SBOM names no root to hang the direct dependencies on. */
    static final String SYNTHETIC_ROOT = "aegis:repository";
    private static final int MAX_MANIFEST_PATH = 400;

    static RepositoryLayout of(CycloneDxBom bom) {
        return of(bom, List.of());
    }

    /** The layout, with what the repository's JavaScript lockfiles say about the packages syft found in them. */
    static RepositoryLayout of(CycloneDxBom bom, List<JsLockfiles.Lockfile> lockfiles) {
        List<Component> components = bom.components() == null ? List.of() : bom.components();
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (Dependency dependency : bom.dependencies() == null ? List.<Dependency>of() : bom.dependencies()) {
            if (dependency != null && dependency.ref() != null && dependency.dependsOn() != null) {
                edges.computeIfAbsent(dependency.ref(), ref -> new LinkedHashSet<>()).addAll(dependency.dependsOn());
            }
        }
        Component root = bom.metadata() == null ? null : bom.metadata().component();
        String rootRef = root == null || root.ref() == null ? SYNTHETIC_ROOT : root.ref();

        // What each lockfile Aegis read resolves: the components syft found in it, by package and version.
        Map<String, JsLockfiles.Lockfile> read = new LinkedHashMap<>();
        lockfiles.forEach(lockfile -> read.put(lockfile.path(), lockfile));
        Map<String, Map<JsLockfiles.Pkg, String>> refsIn = new LinkedHashMap<>();
        Map<String, Set<String>> readIn = new LinkedHashMap<>();
        for (Component component : components) {
            if (component == null || component.ref() == null || component.ref().equals(rootRef)) {
                continue;
            }
            for (String location : locationsOf(component)) {
                if (read.containsKey(location)) {
                    JsLockfiles.Pkg pkg = packageOf(component);
                    if (pkg != null) {
                        refsIn.computeIfAbsent(location, path -> new HashMap<>()).putIfAbsent(pkg, component.ref());
                    }
                    readIn.computeIfAbsent(component.ref(), ref -> new LinkedHashSet<>()).add(location);
                }
            }
        }
        // The lockfiles' own edges, optional dependencies included, which syft leaves out; and their projects.
        Set<String> lockfileProjects = new HashSet<>();
        refsIn.forEach((path, refs) -> {
            JsLockfiles.Lockfile lockfile = read.get(path);
            for (JsLockfiles.Edge edge : lockfile.edges()) {
                String from = refs.get(edge.from());
                String to = refs.get(edge.to());
                if (from != null && to != null && !from.equals(to)) {
                    edges.computeIfAbsent(from, ref -> new LinkedHashSet<>()).add(to);
                }
            }
            for (JsLockfiles.Project project : lockfile.projects()) {
                if (project.self() != null && refs.containsKey(project.self())) {
                    lockfileProjects.add(refs.get(project.self()));
                }
            }
        });
        Set<String> dependedOn = new HashSet<>();
        edges.values().forEach(dependedOn::addAll);

        List<Component> projects = components.stream()
                .filter(component -> component != null && component.ref() != null && !component.ref().equals(rootRef))
                .filter(component -> lockfileProjects.contains(component.ref())
                        || (!dependedOn.contains(component.ref())
                            && !edges.getOrDefault(component.ref(), Set.of()).isEmpty()
                            && isProjectManifest(component)))
                .toList();
        Set<String> projectRefs = new HashSet<>();
        projects.forEach(project -> projectRefs.add(project.ref()));

        Set<String> direct = new LinkedHashSet<>(edges.getOrDefault(rootRef, Set.of()));
        Map<String, Set<String>> manifests = new LinkedHashMap<>();
        Map<String, Set<String>> lockfilesOf = new LinkedHashMap<>();
        for (Component project : projects) {
            String manifest = manifestOf(project);
            for (String dependency : edges.getOrDefault(project.ref(), Set.of())) {
                // A lockfile Aegis read says itself what its projects declare.
                if (dependency != null && !projectRefs.contains(dependency) && !readIn.containsKey(dependency)) {
                    direct.add(dependency);
                    if (manifest != null) {
                        manifests.computeIfAbsent(dependency, ref -> new LinkedHashSet<>()).add(manifest);
                    }
                }
            }
        }
        refsIn.forEach((path, refs) -> {
            for (JsLockfiles.Project project : read.get(path).projects()) {
                for (JsLockfiles.Pkg declared : project.declared()) {
                    String ref = refs.get(declared);
                    if (ref != null && !projectRefs.contains(ref)) {
                        direct.add(ref);
                        manifests.computeIfAbsent(ref, key -> new LinkedHashSet<>()).add(project.manifestPath());
                        lockfilesOf.computeIfAbsent(ref, key -> new LinkedHashSet<>()).add(path);
                    }
                }
            }
        });
        Set<String> dependedOnByPackages = new HashSet<>();
        edges.forEach((ref, targets) -> {
            if (!ref.equals(rootRef) && !projectRefs.contains(ref)) {
                dependedOnByPackages.addAll(targets);
            }
        });
        for (Component component : components) {
            if (component == null || component.ref() == null || projectRefs.contains(component.ref())) {
                continue;
            }
            if (readIn.containsKey(component.ref())) {
                // In a lockfile Aegis read, yet neither declared nor depended on: kept with its lockfile, no manifest.
                if (!direct.contains(component.ref()) && !dependedOnByPackages.contains(component.ref())) {
                    lockfilesOf.computeIfAbsent(component.ref(), ref -> new LinkedHashSet<>())
                            .addAll(readIn.get(component.ref()));
                }
            } else if (!dependedOn.contains(component.ref())) {
                // A library nothing depends on is direct as well, as far as syft's graph tells.
                for (String manifest : manifestsOf(component)) {
                    manifests.computeIfAbsent(component.ref(), ref -> new LinkedHashSet<>()).add(manifest);
                }
            }
        }
        Map<String, List<String>> manifestsByRef = new LinkedHashMap<>();
        manifests.forEach((ref, paths) -> manifestsByRef.put(ref, List.copyOf(paths)));
        Map<String, List<String>> lockfilesByRef = new LinkedHashMap<>();
        lockfilesOf.forEach((ref, paths) -> lockfilesByRef.put(ref, List.copyOf(paths)));
        if (projects.isEmpty() && refsIn.isEmpty() && root != null && root.ref() != null) {
            return new RepositoryLayout(bom, Map.copyOf(manifestsByRef), Map.of(), List.of());
        }

        List<Dependency> dependencies = new ArrayList<>();
        dependencies.add(new Dependency(rootRef, List.copyOf(direct)));
        edges.forEach((ref, targets) -> {
            if (!ref.equals(rootRef) && !projectRefs.contains(ref)) {
                dependencies.add(new Dependency(ref, targets.stream()
                        .filter(target -> !projectRefs.contains(target)).toList()));
            }
        });
        Component repository = root != null && root.ref() != null ? root
                : new Component(SYNTHETIC_ROOT, "application", null, "repository", null, null, null);
        List<Component> remaining = components.stream()
                .filter(component -> component == null || !projectRefs.contains(component.ref()))
                .toList();
        return new RepositoryLayout(
                new CycloneDxBom(bom.bomFormat(), bom.specVersion(), new Metadata(repository), remaining, dependencies),
                Map.copyOf(manifestsByRef), Map.copyOf(lockfilesByRef), projects);
    }

    /**
     * The package a component is, as its lockfile names it — read as the import reads it, from its purl when it has one
     * (an npm scope rides in front of the name), else its name and version; null when it has no version.
     */
    private static JsLockfiles.Pkg packageOf(Component component) {
        PackageUrl purl = null;
        try {
            purl = component.purl() == null || component.purl().isBlank() ? null : PackageUrl.parse(component.purl());
        } catch (RuntimeException unparsable) {
            // a purl the import refuses too; the name and version still say what it is
        }
        String version = component.version() != null && !component.version().isBlank() ? component.version().strip()
                : purl == null ? null : purl.version();
        String name = purl != null ? purl.packageName()
                : component.group() == null || component.group().isBlank() ? component.name()
                : component.group() + "/" + component.name();
        return name == null || version == null ? null : new JsLockfiles.Pkg(name, version);
    }

    private static boolean isProjectManifest(Component component) {
        String foundBy = component.property(FOUND_BY);
        String file = fileName(component.property(LOCATION));
        return ("java-pom-cataloger".equals(foundBy) && file.equals("pom.xml"))
                || ("javascript-lock-cataloger".equals(foundBy)
                    && (file.equals("package-lock.json") || file.equals("npm-shrinkwrap.json")));
    }

    /**
     * The file declaring this package: the {@code package.json} beside the npm lockfile that pins it (what an upgrade
     * edits), else the file itself — a {@code pom.xml} or {@code requirements.txt} Aegis edits, or one it doesn't,
     * such as a {@code gradle.lockfile}, {@code poetry.lock} or a jar, recorded so an upgrade never guesses another.
     * Repository-relative, without a leading slash; null when there is no usable location.
     */
    static String manifestOf(Component component) {
        return manifestAt(component.property(LOCATION));
    }

    /** The files declaring this package at every location syft found it, in its order, each once ({@link #manifestOf}). */
    static List<String> manifestsOf(Component component) {
        Set<String> manifests = new LinkedHashSet<>();
        for (String location : rawLocations(component)) {
            String manifest = manifestAt(location);
            if (manifest != null) {
                manifests.add(manifest);
            }
        }
        return List.copyOf(manifests);
    }

    /** Every file syft found this package in, repository-relative without a leading slash, in its order, each once. */
    static List<String> locationsOf(Component component) {
        Set<String> locations = new LinkedHashSet<>();
        for (String location : rawLocations(component)) {
            if (location != null && !location.isBlank() && location.length() <= MAX_MANIFEST_PATH) {
                locations.add(location.strip().replaceFirst("^/+", ""));
            }
        }
        return List.copyOf(locations);
    }

    private static Iterable<String> rawLocations(Component component) {
        Map<Integer, String> byIndex = new TreeMap<>();
        for (Property property : component.properties() == null ? List.<Property>of() : component.properties()) {
            Matcher location = property == null || property.name() == null ? null
                    : LOCATION_PATH.matcher(property.name());
            if (location != null && location.matches()) {
                byIndex.putIfAbsent(Integer.parseInt(location.group(1)), property.value());
            }
        }
        return byIndex.values();
    }

    private static String manifestAt(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String path = location.strip().replaceFirst("^/+", "");
        int slash = path.lastIndexOf('/');
        String directory = slash < 0 ? "" : path.substring(0, slash + 1);
        String manifest = switch (fileName(path)) {
            case "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "package.json" ->
                    directory + "package.json";
            default -> path;
        };
        return manifest == null || manifest.isEmpty() || manifest.length() > MAX_MANIFEST_PATH ? null : manifest;
    }

    private static String fileName(String path) {
        if (path == null) {
            return "";
        }
        return path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }
}
