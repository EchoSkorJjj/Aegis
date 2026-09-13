package com.aegis.core.inventory.sbom;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.common.ApiExceptions;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.sbom.CycloneDxBom.Component;
import com.aegis.core.inventory.sbom.CycloneDxBom.Dependency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SBOM → inventory mapping against an in-memory store (no database, no Docker). */
class SbomImportServiceTest {

    static final String FIXTURE = "fixtures/sbom/storefront.cdx.json";

    private final InventoryStore store = mock(InventoryStore.class);
    private final SbomImportService importer = new SbomImportService(new InventoryService(store), store);
    private final UUID serviceId = UUID.randomUUID();

    private final Map<String, SoftwareComponent> componentsByCoordinates = new HashMap<>();
    private final Set<List<UUID>> dependencyEdges = new HashSet<>();
    private final Set<UUID> serviceComponents = new HashSet<>();

    @BeforeEach
    void inMemoryStore() {
        when(store.findBusinessService(serviceId))
                .thenReturn(Optional.of(new BusinessService(serviceId, "Storefront", BusinessCriticality.HIGH)));
        when(store.findSoftwareComponentByCoordinates(any(), any(), any())).thenAnswer(call -> Optional.ofNullable(
                componentsByCoordinates.get(key(call.getArgument(0), call.getArgument(1), call.getArgument(2)))));
        when(store.save(any(SoftwareComponent.class))).thenAnswer(call -> {
            SoftwareComponent component = call.getArgument(0);
            componentsByCoordinates.put(
                    key(component.getEcosystem(), component.getName(), component.getVersion()), component);
            return component;
        });
        when(store.componentDependencyExists(any(), any()))
                .thenAnswer(call -> dependencyEdges.contains(List.of(call.getArgument(0), call.getArgument(1))));
        doAnswer(call -> dependencyEdges.add(List.of(call.getArgument(0), call.getArgument(1))))
                .when(store).addComponentDependency(any(), any());
        when(store.serviceComponentExists(any(), any()))
                .thenAnswer(call -> serviceComponents.contains((UUID) call.getArgument(1)));
        doAnswer(call -> serviceComponents.add(call.getArgument(1))).when(store).addServiceComponent(any(), any());
    }

    @Test
    void importsFixtureLinkingOnlyTopLevelComponentsToTheService() {
        SbomImportResult result = importer.importSbom(serviceId, fixture());

        assertThat(result).isEqualTo(new SbomImportResult(serviceId, 4, 0, 1, 2, 2));
        SoftwareComponent web = component("npm", "fixture-web-framework", "2.0.0");
        SoftwareComponent http = component("npm", "@fixture/http-client", "1.1.0");
        SoftwareComponent redirects = component("npm", "fixture-redirects", "0.9.0");
        // Version taken from the purl when the component has no "version" field.
        SoftwareComponent logger = component("npm", "fixture-logger", "3.0.0");

        assertThat(dependencyEdges).containsExactlyInAnyOrder(
                List.of(web.getId(), http.getId()),
                List.of(http.getId(), redirects.getId()));
        assertThat(serviceComponents).containsExactlyInAnyOrder(web.getId(), logger.getId());
        assertThat(componentsByCoordinates.keySet()).noneMatch(key -> key.contains("storefront-fixture"));
    }

    @Test
    void reimportingTheSameSbomChangesNothing() {
        importer.importSbom(serviceId, fixture());
        Set<List<UUID>> edgesAfterFirstImport = Set.copyOf(dependencyEdges);

        SbomImportResult second = importer.importSbom(serviceId, fixture());

        assertThat(second).isEqualTo(new SbomImportResult(serviceId, 0, 4, 1, 0, 0));
        assertThat(componentsByCoordinates).hasSize(4);
        assertThat(dependencyEdges).isEqualTo(edgesAfterFirstImport);
        assertThat(serviceComponents).hasSize(2);
    }

    @Test
    void withoutADependencyGraphEveryComponentIsTopLevel() {
        CycloneDxBom flat = bom(null, List.of(
                library("a", "pkg:maven/com.example/a@1.0"),
                library("b", "pkg:maven/com.example/b@2.0")), null);

        SbomImportResult result = importer.importSbom(serviceId, flat);

        assertThat(result.serviceComponentsLinked()).isEqualTo(2);
        assertThat(component("maven", "com.example:a", "1.0")).isNotNull();
    }

    @Test
    void rootDependenciesStayDirectEvenWhenAnotherComponentAlsoDependsOnThem() {
        CycloneDxBom bom = bom(new Component("app", "application", null, "app", "1", null, null),
                List.of(library("framework", "pkg:npm/framework@1"), library("util", "pkg:npm/util@1")),
                List.of(new Dependency("app", List.of("framework", "util")),
                        new Dependency("framework", List.of("util"))));

        importer.importSbom(serviceId, bom);

        assertThat(serviceComponents).containsExactlyInAnyOrder(
                component("npm", "framework", "1").getId(), component("npm", "util", "1").getId());
        assertThat(dependencyEdges).hasSize(1);
    }

    @Test
    void componentsWithoutPurlUseTheGenericEcosystemAndGroupedName() {
        CycloneDxBom bom = bom(null, List.of(new Component("x", "library", "acme", "widget", "5.1", null, null)), null);

        importer.importSbom(serviceId, bom);

        assertThat(component("generic", "acme/widget", "5.1")).isNotNull();
    }

    @Test
    void coordinatesDifferingOnlyByCaseCollapseToOneComponent() {
        CycloneDxBom bom = bom(null, List.of(
                library("upper", "pkg:npm/Lodash@4.17.20"),
                library("lower", "pkg:npm/lodash@4.17.20")),
                List.of(new Dependency("upper", List.of("lower"))));

        SbomImportResult result = importer.importSbom(serviceId, bom);

        assertThat(result.componentsCreated()).isEqualTo(1);
        assertThat(dependencyEdges).isEmpty();
    }

    @Test
    void unknownServiceIsRejectedBeforeAnyWrite() {
        UUID missing = UUID.randomUUID();
        when(store.findBusinessService(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> importer.importSbom(missing, fixture())).isInstanceOf(ApiExceptions.NotFound.class);
        verify(store, never()).save(any(SoftwareComponent.class));
    }

    @Test
    void rejectsDocumentsThatAreNotCycloneDx() {
        assertThatThrownBy(() -> importer.importSbom(serviceId, new CycloneDxBom("SPDX", "2.3", null, null, null)))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("CycloneDX");
        assertThatThrownBy(() -> importer.importSbom(serviceId, null)).isInstanceOf(ApiExceptions.BadRequest.class);
    }

    @Test
    void rejectsUntrustedValuesTheInventoryCannotHold() {
        assertThatThrownBy(() -> importer.importSbom(serviceId,
                bom(null, List.of(library("bad", "npm:lodash@1")), null)))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageStartingWith("Invalid package URL");
        assertThatThrownBy(() -> importer.importSbom(serviceId,
                bom(null, List.of(library("long", "pkg:npm/" + "n".repeat(300) + "@1")), null)))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("name exceeds 255");
        assertThatThrownBy(() -> importer.importSbom(serviceId,
                bom(null, List.of(new Component("anon", "library", null, null, "1.0", null, null)), null)))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("missing both a purl and a name");
    }

    @Test
    void capsTheNumberOfComponents() {
        List<Component> components = new ArrayList<>();
        for (int i = 0; i <= SbomImportService.MAX_COMPONENTS; i++) {
            components.add(library("c" + i, "pkg:npm/c" + i + "@1"));
        }

        assertThatThrownBy(() -> importer.importSbom(serviceId, bom(null, components, null)))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("more than " + SbomImportService.MAX_COMPONENTS);
        verify(store, never()).save(any(SoftwareComponent.class));
    }

    static CycloneDxBom fixture() {
        try (InputStream in = SbomImportServiceTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
            return JsonMapper.builder().build().readValue(in, CycloneDxBom.class);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private SoftwareComponent component(String ecosystem, String name, String version) {
        SoftwareComponent component = componentsByCoordinates.get(key(ecosystem, name, version));
        assertThat(component).as("component %s %s %s", ecosystem, name, version).isNotNull();
        return component;
    }

    private static CycloneDxBom bom(Component root, List<Component> components, List<Dependency> dependencies) {
        return new CycloneDxBom("CycloneDX", "1.5", root == null ? null : new CycloneDxBom.Metadata(root),
                components, dependencies);
    }

    private static Component library(String ref, String purl) {
        return new Component(ref, "library", null, null, null, purl, null);
    }

    private static String key(String ecosystem, String name, String version) {
        return (ecosystem + "|" + name + "|" + version).toLowerCase(Locale.ROOT);
    }
}
