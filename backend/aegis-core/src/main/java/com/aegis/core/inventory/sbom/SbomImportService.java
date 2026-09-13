package com.aegis.core.inventory.sbom;

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

import com.aegis.core.common.ApiExceptions;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.sbom.CycloneDxBom.Component;
import com.aegis.core.inventory.sbom.CycloneDxBom.Dependency;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merges a CycloneDX SBOM into the inventory map for one business service. The import is additive
 * and idempotent: components are matched by (ecosystem, name, version) and existing edges are kept.
 *
 * <p>The service is linked only to its top-level components — the SBOM root's direct dependencies
 * plus any component nothing else depends on — so deeper libraries stay reachable transitively
 * through dependency edges, which is what the impact query reports as a transitive hit.
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

    @Transactional
    public SbomImportResult importSbom(UUID serviceId, CycloneDxBom bom) {
        inventory.getBusinessService(serviceId);
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
        Set<List<UUID>> edges = new HashSet<>();
        int declaredEdges = 0;
        int dependenciesCreated = 0;
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
                if (edges.add(List.of(from, to)) && !store.componentDependencyExists(from, to)) {
                    store.addComponentDependency(from, to);
                    dependenciesCreated++;
                }
            }
        }

        Set<UUID> topLevel = new LinkedHashSet<>(rootDependencies);
        idsByCoordinates.values().stream().filter(id -> !dependedOn.contains(id)).forEach(topLevel::add);
        int linked = 0;
        for (UUID componentId : topLevel) {
            if (!store.serviceComponentExists(serviceId, componentId)) {
                store.addServiceComponent(serviceId, componentId);
                linked++;
            }
        }

        return new SbomImportResult(serviceId, created, matched, skipped, dependenciesCreated, linked);
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
            if (version == null) {
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
