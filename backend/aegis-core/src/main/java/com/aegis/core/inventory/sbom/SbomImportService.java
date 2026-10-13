package com.aegis.core.inventory.sbom;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.InventoryStore.ComponentDependencyLink;
import com.aegis.core.inventory.InventoryStore.DependencyChanges;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.SbomImportSource;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.sbom.CycloneDxBom.Component;
import com.aegis.core.inventory.sbom.CycloneDxBom.Dependency;
import com.aegis.core.platform.common.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merges a CycloneDX SBOM into the inventory map for one business service. The import is idempotent:
 * components are matched by (ecosystem, name, version) and existing edges are kept.
 *
 * <p>The service is linked only to its top-level components — the SBOM root's direct dependencies
 * plus any component nothing else depends on — so deeper libraries stay reachable transitively
 * through dependency edges, which is what the impact query reports as a transitive hit. The edges are the
 * service's own: another service's lockfile may resolve the same library to different dependencies.
 *
 * <p>An uploaded SBOM only adds. An import from the service's repository reads everything the repository
 * declares, so it also unlinks the direct dependencies an earlier import from it linked and the repository no
 * longer declares — an upgraded library's old version — and removes the dependency edges it no longer declares.
 * Components stay in the inventory, and a component linked by hand or by an upload stays linked.
 *
 * <p>Each import is recorded on the service — when, and how it got in ({@link SbomImportSource}).
 */
@Service
public class SbomImportService {

    static final int MAX_COMPONENTS = 5_000;
    static final int MAX_DEPENDENCY_EDGES = 50_000;
    static final String GENERIC_ECOSYSTEM = "generic";

    private final InventoryService inventory;
    private final InventoryStore store;

    public SbomImportService(InventoryService inventory, InventoryStore store) {
        this.inventory = inventory;
        this.store = store;
    }

    /** A CycloneDX file posted to the API (or the demo seed), recorded as {@link SbomImportSource#UPLOAD}. */
    @Transactional
    public SbomImportResult importSbom(UUID serviceId, CycloneDxBom bom) {
        return importSbom(serviceId, bom, SbomImportSource.UPLOAD);
    }

    @Transactional
    public SbomImportResult importSbom(UUID serviceId, CycloneDxBom bom, SbomImportSource source) {
        return importSbom(serviceId, bom, source, null);
    }

    /**
     * An SBOM generated from the service's repository ({@link RepositoryLayout}): each direct dependency
     * is linked with every manifest that declares it and every JavaScript lockfile that resolves it; what an earlier import from the repository linked and the
     * repository no longer declares is unlinked, and so are the repository's own packages, which an earlier
     * import may have linked as components.
     */
    @Transactional
    public SbomImportResult importRepository(UUID serviceId, RepositoryLayout layout, SbomImportSource source) {
        return importSbom(serviceId, layout.bom(), source, layout);
    }

    /** {@code repository} is the layout of an import from the service's repository; null for an upload. */
    private SbomImportResult importSbom(UUID serviceId, CycloneDxBom bom, SbomImportSource source,
            RepositoryLayout repository) {
        BusinessService service = inventory.getBusinessService(serviceId);
        if (bom == null || !"CycloneDX".equals(bom.bomFormat())) {
            throw new ApiExceptions.BadRequest("Expected a CycloneDX JSON SBOM (bomFormat \"CycloneDX\")");
        }

        String rootRef = bom.metadata() == null || bom.metadata().component() == null
                ? null : bom.metadata().component().ref();

        Map<String, UUID> idsByRef = new HashMap<>();
        Map<String, UUID> idsByCoordinates = new LinkedHashMap<>();
        int created = 0;
        int matched = 0;
        int skipped = 0;
        for (Component component : flatten(bom.components())) {
            if (rootRef != null && rootRef.equals(component.ref())) {
                continue;
            }
            Coordinates coordinates = Coordinates.of(component);
            if (coordinates == null) {
                skipped++;
                continue;
            }
            UUID id = idsByCoordinates.get(coordinates.key());
            if (id == null) {
                SoftwareComponent existing = store.findSoftwareComponentByCoordinates(
                        coordinates.ecosystem(), coordinates.name(), coordinates.version()).orElse(null);
                if (existing != null) {
                    matched++;
                    id = existing.getId();
                } else {
                    created++;
                    id = inventory.createSoftwareComponent(
                            coordinates.ecosystem(), coordinates.name(), coordinates.version()).getId();
                }
                idsByCoordinates.put(coordinates.key(), id);
            }
            if (component.ref() != null) {
                idsByRef.putIfAbsent(component.ref(), id);
            }
            if (component.purl() != null) {
                idsByRef.putIfAbsent(component.purl(), id);
            }
        }

        Set<UUID> rootDependencies = new LinkedHashSet<>();
        Set<UUID> dependedOn = new HashSet<>();
        Set<ComponentDependencyLink> edges = new LinkedHashSet<>();
        int declaredEdges = 0;
        for (Dependency dependency : nullSafe(bom.dependencies())) {
            if (dependency == null || dependency.ref() == null) {
                continue;
            }
            boolean fromRoot = dependency.ref().equals(rootRef);
            UUID from = idsByRef.get(dependency.ref());
            for (String targetRef : nullSafe(dependency.dependsOn())) {
                if (++declaredEdges > MAX_DEPENDENCY_EDGES) {
                    throw new ApiExceptions.BadRequest(
                            "SBOM declares more than " + MAX_DEPENDENCY_EDGES + " dependency edges");
                }
                // Dangling refs (to components we skipped or that the SBOM never declared) are ignored.
                UUID to = targetRef == null ? null : idsByRef.get(targetRef);
                if (to == null) {
                    continue;
                }
                if (fromRoot) {
                    rootDependencies.add(to);
                    continue;
                }
                if (from == null || from.equals(to)) {
                    continue;
                }
                dependedOn.add(to);
                edges.add(new ComponentDependencyLink(from, to, serviceId));
            }
        }
        DependencyChanges dependencies = store.recordServiceDependencies(serviceId, edges, repository != null);

        Set<UUID> topLevel = new LinkedHashSet<>(rootDependencies);
        idsByCoordinates.values().stream().filter(id -> !dependedOn.contains(id)).forEach(topLevel::add);
        // Several bom-refs can be one component (the same version in two lockfiles): every manifest counts.
        Map<UUID, Set<String>> manifests = new HashMap<>();
        Map<UUID, Set<String>> lockfiles = new HashMap<>();
        if (repository != null) {
            repository.manifestsByRef().forEach((ref, paths) -> {
                UUID id = idsByRef.get(ref);
                if (id != null) {
                    manifests.computeIfAbsent(id, component -> new LinkedHashSet<>()).addAll(paths);
                }
            });
            repository.lockfilesByRef().forEach((ref, paths) -> {
                UUID id = idsByRef.get(ref);
                if (id != null) {
                    lockfiles.computeIfAbsent(id, component -> new LinkedHashSet<>()).addAll(paths);
                }
            });
        }
        int linked = 0;
        for (UUID componentId : topLevel) {
            boolean existing = store.serviceComponentExists(serviceId, componentId);
            if (repository != null) {
                store.linkRepositoryComponent(serviceId, componentId,
                        List.copyOf(manifests.getOrDefault(componentId, Set.of())),
                        List.copyOf(lockfiles.getOrDefault(componentId, Set.of())));
            } else if (!existing) {
                store.addServiceComponent(serviceId, componentId);
            }
            if (!existing) {
                linked++;
            }
        }
        int unlinked = 0;
        if (repository != null) {
            for (Component project : repository.projects()) {
                Coordinates coordinates = Coordinates.of(project);
                SoftwareComponent component = coordinates == null ? null : store.findSoftwareComponentByCoordinates(
                        coordinates.ecosystem(), coordinates.name(), coordinates.version()).orElse(null);
                if (component != null && store.removeServiceComponent(serviceId, component.getId())) {
                    unlinked++;
                }
            }
            unlinked += store.unlinkRepositoryComponentsExcept(serviceId, topLevel);
        }

        service.recordSbomImport(source, Instant.now());
        store.save(service);
        return new SbomImportResult(serviceId, created, matched, skipped, dependencies.added(), dependencies.removed(),
                linked, unlinked);
    }

    /** CycloneDX components may nest (assemblies); walk them iteratively so depth is never a stack risk. */
    private static List<Component> flatten(List<Component> roots) {
        List<Component> all = new ArrayList<>();
        Deque<Component> pending = new ArrayDeque<>();
        nullSafe(roots).stream().filter(Objects::nonNull).forEach(pending::addLast);
        while (!pending.isEmpty()) {
            Component component = pending.pollFirst();
            all.add(component);
            if (all.size() > MAX_COMPONENTS) {
                throw new ApiExceptions.BadRequest("SBOM declares more than " + MAX_COMPONENTS + " components");
            }
            nullSafe(component.components()).stream().filter(Objects::nonNull).forEach(pending::addLast);
        }
        return all;
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    record Coordinates(String ecosystem, String name, String version) {

        /** Null when the component has no version — the inventory cannot register it. */
        static Coordinates of(Component component) {
            PackageUrl purl = isBlank(component.purl()) ? null : PackageUrl.parse(component.purl());
            String version = !isBlank(component.version()) ? component.version().trim()
                    : purl == null ? null : purl.version();
            // syft reports a version it could not resolve (a parent-managed Maven one) as UNKNOWN.
            if (version == null || version.equalsIgnoreCase("UNKNOWN")) {
                return null;
            }
            String name;
            if (purl != null) {
                name = purl.packageName();
            } else if (isBlank(component.name())) {
                throw new ApiExceptions.BadRequest("SBOM component is missing both a purl and a name");
            } else {
                name = isBlank(component.group())
                        ? component.name().trim() : component.group().trim() + "/" + component.name().trim();
            }
            String ecosystem = purl == null ? GENERIC_ECOSYSTEM : purl.type();
            return new Coordinates(
                    requireLength(ecosystem, "ecosystem", 64),
                    requireLength(name, "name", 255),
                    requireLength(version, "version", 128));
        }

        String key() {
            return (ecosystem + " " + name + " " + version).toLowerCase(Locale.ROOT);
        }

        private static String requireLength(String value, String field, int max) {
            if (value.length() > max) {
                throw new ApiExceptions.BadRequest(
                        "SBOM component " + field + " exceeds " + max + " characters");
            }
            return value;
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
