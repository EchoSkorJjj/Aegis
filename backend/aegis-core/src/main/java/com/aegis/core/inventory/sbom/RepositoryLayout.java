package com.aegis.core.inventory.sbom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.aegis.core.inventory.sbom.CycloneDxBom.Component;
import com.aegis.core.inventory.sbom.CycloneDxBom.Dependency;
import com.aegis.core.inventory.sbom.CycloneDxBom.Metadata;

/**
 * A scanned repository's layout, read from syft's SBOM. Syft scans a directory, so it reports the
 * repository's own packages as components — a Maven module (found at its own {@code pom.xml}) or an npm
 * {@code package-lock.json}'s root project — with the libraries they declare below them. Taken literally
 * that makes every library a service uses look transitive, so none could ever be upgraded, and it gives two
 * repositories' private {@code client@0.0.0} the same coordinates.
 *
 * <p>{@link #of} turns them back into what they are, the repository itself: a project package leaves the
 * components, what it declares becomes a direct dependency of the service, and each direct dependency
 * remembers the manifest that declares it ({@code client/package.json}, {@code service/pom.xml}) for an
 * upgrade to edit. A project package is a dependency-graph root that depends on something, found by syft's
 * Maven cataloger at a {@code pom.xml} or its npm lockfile cataloger at a {@code package-lock.json} /
 * {@code npm-shrinkwrap.json}. (pnpm and yarn lockfiles name no project, so their direct dependencies are
 * roots already; syft leaves dev dependencies out, so a dev tool is never mistaken for a project.)
 *
 * @param bom            the SBOM with the project packages removed and their dependencies hung on the root
 * @param manifestsByRef the manifest declaring each direct dependency, by bom-ref, where it is known
 * @param projects       the project packages removed — a re-import unlinks them from the service
 */
record RepositoryLayout(CycloneDxBom bom, Map<String, String> manifestsByRef, List<Component> projects) {

    private static final String LOCATION = "syft:location:0:path";
    private static final String FOUND_BY = "syft:package:foundBy";
    /** The repository itself, when the SBOM names no root to hang the direct dependencies on. */
    static final String SYNTHETIC_ROOT = "aegis:repository";
    private static final int MAX_MANIFEST_PATH = 400;

    static RepositoryLayout of(CycloneDxBom bom) {
        List<Component> components = bom.components() == null ? List.of() : bom.components();
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (Dependency dependency : bom.dependencies() == null ? List.<Dependency>of() : bom.dependencies()) {
            if (dependency != null && dependency.ref() != null && dependency.dependsOn() != null) {
                edges.computeIfAbsent(dependency.ref(), ref -> new ArrayList<>()).addAll(dependency.dependsOn());
            }
        }
        Set<String> dependedOn = new HashSet<>();
        edges.values().forEach(dependedOn::addAll);
        Component root = bom.metadata() == null ? null : bom.metadata().component();
        String rootRef = root == null || root.ref() == null ? SYNTHETIC_ROOT : root.ref();

        List<Component> projects = components.stream()
                .filter(component -> component != null && component.ref() != null && !component.ref().equals(rootRef))
                .filter(component -> !dependedOn.contains(component.ref())
                        && !edges.getOrDefault(component.ref(), List.of()).isEmpty()
                        && isProjectManifest(component))
                .toList();
        Set<String> projectRefs = new HashSet<>();
        projects.forEach(project -> projectRefs.add(project.ref()));

        Set<String> direct = new LinkedHashSet<>(edges.getOrDefault(rootRef, List.of()));
        Map<String, String> manifests = new LinkedHashMap<>();
        for (Component project : projects) {
            String manifest = manifestOf(project);
            for (String dependency : edges.get(project.ref())) {
                if (dependency != null && !projectRefs.contains(dependency)) {
                    direct.add(dependency);
                    if (manifest != null) {
                        manifests.putIfAbsent(dependency, manifest);
                    }
                }
            }
        }
        // A library nothing depends on is direct as well (a pnpm or yarn project's own dependencies).
        for (Component component : components) {
            if (component != null && component.ref() != null && !projectRefs.contains(component.ref())
                    && !dependedOn.contains(component.ref())) {
                String manifest = manifestOf(component);
                if (manifest != null) {
                    manifests.putIfAbsent(component.ref(), manifest);
                }
            }
        }
        if (projects.isEmpty() && root != null && root.ref() != null) {
            return new RepositoryLayout(bom, Map.copyOf(manifests), List.of());
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
                Map.copyOf(manifests), projects);
    }

    private static boolean isProjectManifest(Component component) {
        String foundBy = component.property(FOUND_BY);
        String file = fileName(component.property(LOCATION));
        return ("java-pom-cataloger".equals(foundBy) && file.equals("pom.xml"))
                || ("javascript-lock-cataloger".equals(foundBy)
                    && (file.equals("package-lock.json") || file.equals("npm-shrinkwrap.json")));
    }

    /**
     * The manifest an upgrade of this package edits, where it is one Aegis can edit: the {@code pom.xml}
     * that declares it, the {@code package.json} beside the lockfile that pins it, or a
     * {@code requirements.txt}. Repository-relative, without a leading slash; null otherwise.
     */
    static String manifestOf(Component component) {
        String location = component.property(LOCATION);
        if (location == null || location.isBlank()) {
            return null;
        }
        String path = location.strip().replaceFirst("^/+", "");
        int slash = path.lastIndexOf('/');
        String directory = slash < 0 ? "" : path.substring(0, slash + 1);
        String manifest = switch (fileName(path)) {
            case "pom.xml", "requirements.txt" -> path;
            case "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "package.json" ->
                    directory + "package.json";
            default -> null;
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
