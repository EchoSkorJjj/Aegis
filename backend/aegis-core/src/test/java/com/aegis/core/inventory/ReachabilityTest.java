package com.aegis.core.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.inventory.CodeUsage.Status;
import com.aegis.core.inventory.CodeUsage.Usage;
import com.aegis.core.inventory.DependencyGraph.ServiceGraph;
import com.aegis.core.inventory.InventoryStore.ServiceComponentLink;
import com.aegis.core.inventory.InventoryStore.ServiceImport;
import com.aegis.core.inventory.Reachability.Verdict;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ImportScan;
import com.aegis.core.inventory.domain.ImportScan.ModuleImports;
import com.aegis.core.inventory.domain.ImportScan.Site;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.domain.SbomImportSource;
import com.aegis.core.inventory.domain.SoftwareComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether a service's code reaches a package (E11-4) through its own dependency graph: imported itself; not imported
 * only when nothing above it in the graph is imported either; unknown otherwise — a chain whose direct dependency the
 * code imports, one whose direct dependency it doesn't, and a cycle.
 */
class ReachabilityTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    private final InventoryStore store = mock(InventoryStore.class);
    private final Reachability reachability = new Reachability(store, mock(DependencyGraph.class), new CodeUsage(store));

    private final BusinessService service = new BusinessService(UUID.randomUUID(), "Web", BusinessCriticality.HIGH);
    private final Map<String, SoftwareComponent> components = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> dependsOn = new HashMap<>();
    private final List<ServiceComponentLink> direct = new ArrayList<>();

    @BeforeEach
    void aScannedService() {
        service.linkRepository(RepositoryUrl.parse("https://github.com/acme/web", List.of("github.com")), null);
        service.recordSbomImport(SbomImportSource.PUBLIC, Instant.now());
        service.recordImportScan(new ImportScan(REVISION, List.of("npm"), 40, null, List.of()), Instant.now());
        when(store.findSoftwareComponentsByIds(any())).thenAnswer(call -> {
            Map<UUID, SoftwareComponent> byId = new HashMap<>();
            components.values().forEach(component -> byId.put(component.getId(), component));
            return byId;
        });
        // What the code imports: next, in two places, and lodash.
        when(store.findServiceImports(anyCollection(), anyCollection())).thenReturn(List.of(
                new ServiceImport(service.getId(), new ModuleImports("npm", "next", 2,
                        List.of(new Site("app/page.tsx", 1), new Site("app/layout.tsx", 3)))),
                new ServiceImport(service.getId(), new ModuleImports("npm", "lodash", 1,
                        List.of(new Site("lib/util.ts", 7))))));
    }

    @Test
    void aPackageTheCodeImportsIsImportedWithTheLines() {
        linked("lodash", true);

        Verdict lodash = verdict("lodash");

        assertThat(lodash.status()).isEqualTo(Status.IMPORTED);
        assertThat(lodash.through()).isNull();
        assertThat(lodash.usage().sites()).extracting(CodeUsage.Site::path).containsExactly("lib/util.ts");
    }

    @Test
    void aChainWhoseDirectDependencyTheCodeImportsIsUnknownThroughIt() {
        linked("next", true);
        dependsOn("next", "postcss");

        Verdict postcss = verdict("postcss");

        // Not imported itself, but next pulls it in and the code imports next: whether that reaches it is unknown.
        assertThat(postcss.status()).isEqualTo(Status.UNKNOWN);
        assertThat(postcss.reason()).isEqualTo("postcss isn't imported itself, but it comes in through next, which "
                + "the code imports");
        assertThat(postcss.through()).isEqualTo("next");
        assertThat(postcss.usage().siteCount()).isEqualTo(2);
        assertThat(postcss.usage().sites()).extracting(CodeUsage.Site::url).first()
                .isEqualTo("https://github.com/acme/web/blob/" + REVISION + "/app/layout.tsx#L3");
    }

    @Test
    void aChainWhoseDirectDependencyTheCodeDoesNotImportIsNotImported() {
        linked("vite", true);
        dependsOn("vite", "rollup");
        dependsOn("rollup", "esbuild");

        Verdict esbuild = verdict("esbuild");

        assertThat(esbuild.status()).isEqualTo(Status.NOT_IMPORTED);
        assertThat(esbuild.reason()).isEqualTo("Nothing in the code Aegis read imports esbuild, nor rollup or vite, "
                + "which pull it in");
        assertThat(esbuild.through()).isNull();
    }

    @Test
    void aCycleEndsAndCountsEveryPackageInIt() {
        linked("webpack", true);
        dependsOn("webpack", "watchpack");
        dependsOn("watchpack", "chokidar");
        dependsOn("chokidar", "watchpack");

        assertThat(verdict("chokidar")).extracting(Verdict::status, Verdict::reason).containsExactly(
                Status.NOT_IMPORTED,
                "Nothing in the code Aegis read imports chokidar, nor watchpack or webpack, which pull it in");

        // Import what the cycle hangs from, and nothing in it is not imported any more.
        linked("next", true);
        dependsOn("next", "watchpack");
        assertThat(verdict("chokidar")).extracting(Verdict::status, Verdict::through)
                .containsExactly(Status.UNKNOWN, "next");
    }

    @Test
    void aDirectDependencyAnotherPackagePullsInIsUnknownWhileTheCodeImportsThatOne() {
        // react is declared and imported by nothing, but next — which the code imports — pulls it in too.
        linked("react", true);
        linked("next", true);
        dependsOn("next", "react");

        assertThat(verdict("react").status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void whatTheCodeReadCannotSpeakForStaysUnknown() {
        // Linked by hand, not by the import from the repository: the code read may have nothing to do with it.
        linked("legacy", false);
        dependsOn("legacy", "qs");

        Verdict qs = verdict("qs");

        assertThat(qs.status()).isEqualTo(Status.UNKNOWN);
        assertThat(qs.reason()).startsWith("It was linked by hand or by an uploaded SBOM");
        assertThat(qs.through()).isNull();
    }

    @Test
    void aPackagePulledInByOneWhoseImportsAreUnknownIsUnknownThroughIt() {
        Usage notImported = new Usage(Status.NOT_IMPORTED, "Nothing in the code Aegis read imports urllib3",
                List.of("urllib3"), 0, List.of());
        Usage unknown = new Usage(Status.UNKNOWN, "Nothing imports some_dist, but Aegis doesn't know for sure which "
                + "name some-dist is imported as", List.of("some_dist"), 0, List.of());

        Verdict urllib3 = Reachability.verdict("urllib3", notImported, Map.of("some-dist", unknown));

        assertThat(urllib3.status()).isEqualTo(Status.UNKNOWN);
        assertThat(urllib3.through()).isEqualTo("some-dist");
        assertThat(urllib3.reason()).isEqualTo("Nothing imports urllib3 itself, but it comes in through some-dist, and "
                + "whether the code imports that is unknown: nothing imports some_dist, but Aegis doesn't know for sure "
                + "which name some-dist is imported as");
    }

    @Test
    void aChangeMatchingSeveralComponentsIsAsReachableAsTheMostReachableOfThem() {
        linked("next", true);
        dependsOn("next", "postcss");
        linked("vite", true);
        dependsOn("vite", "esbuild");

        Reachability.Reached reached = read();

        assertThat(reached.strongest(service.getId(), List.of(id("esbuild"), id("postcss"))).status())
                .isEqualTo(Status.UNKNOWN);
        assertThat(reached.strongest(service.getId(), List.of(id("esbuild"))).status())
                .isEqualTo(Status.NOT_IMPORTED);
        // Nothing read for the service at all: unknown, never not imported.
        assertThat(Reachability.Reached.NONE.verdict(service.getId(), id("esbuild")).status())
                .isEqualTo(Status.UNKNOWN);
    }

    private void linked(String name, boolean fromRepository) {
        direct.add(new ServiceComponentLink(service.getId(), component(name).getId(), List.of("package.json"),
                List.of("package-lock.json"), fromRepository));
    }

    private void dependsOn(String name, String dependency) {
        dependsOn.computeIfAbsent(component(name).getId(), id -> new LinkedHashSet<>()).add(component(dependency).getId());
    }

    private SoftwareComponent component(String name) {
        return components.computeIfAbsent(name, key -> new SoftwareComponent(UUID.randomUUID(), "npm", key, "1.0.0"));
    }

    private UUID id(String name) {
        return component(name).getId();
    }

    private Reachability.Reached read() {
        Set<UUID> all = new LinkedHashSet<>();
        components.values().forEach(component -> all.add(component.getId()));
        ServiceGraph graph = new ServiceGraph(direct, dependsOn, all);
        return reachability.of(Map.of(service.getId(), service), Map.of(service.getId(), graph),
                Map.of(service.getId(), all));
    }

    private Verdict verdict(String name) {
        return read().verdict(service.getId(), id(name));
    }
}
