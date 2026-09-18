package com.aegis.core.inventory.demo;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.aegis.core.inventory.demo.DemoEstateSeeder.Estate;
import com.aegis.core.inventory.demo.DemoEstateSeeder.ServiceSpec;
import com.aegis.core.inventory.sbom.CycloneDxBom;
import com.aegis.core.inventory.sbom.CycloneDxBom.Component;
import com.aegis.core.inventory.sbom.CycloneDxBom.Dependency;
import com.aegis.core.inventory.sbom.PackageUrl;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the shipped demo-data fixtures so a typo fails fast here, not at container startup. */
class DemoEstateFixturesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void estateDescribesUniqueServicesWithOwnersCriticalityAndExposure() {
        Estate estate = read(DemoEstateSeeder.ESTATE_RESOURCE, Estate.class);

        assertThat(estate.services()).extracting(ServiceSpec::name).doesNotHaveDuplicates()
                .contains("Payments API", "Order Service", "Customer Portal", "Internal Reporting", "Marketing Site");
        assertThat(estate.services()).allSatisfy(service -> {
            assertThat(service.criticality()).isNotNull();
            // Scoring multiplies exposure in, so a demo service without one would silently take the
            // default and make the seeded estate a worse illustration than it should be.
            assertThat(service.exposure()).as("%s exposure", service.name()).isNotNull();
            assertThat(service.owners()).isNotEmpty();
        });
    }

    @Test
    void everyReferencedSbomIsAConsistentCycloneDxDocument() {
        List<String> sboms = read(DemoEstateSeeder.ESTATE_RESOURCE, Estate.class).services().stream()
                .map(ServiceSpec::sbom).filter(path -> path != null).toList();
        assertThat(sboms).isNotEmpty();

        for (String path : sboms) {
            CycloneDxBom bom = read(path, CycloneDxBom.class);
            assertThat(bom.bomFormat()).as(path).isEqualTo("CycloneDX");

            Set<String> refs = new HashSet<>();
            refs.add(bom.metadata().component().ref());
            for (Component component : bom.components()) {
                assertThat(refs.add(component.ref())).as("%s duplicate ref %s", path, component.ref()).isTrue();
                PackageUrl purl = PackageUrl.parse(component.purl());
                assertThat(component.version()).as("%s %s", path, component.ref()).isEqualTo(purl.version());
            }
            for (Dependency dependency : bom.dependencies()) {
                assertThat(refs).as(path).contains(dependency.ref());
                assertThat(refs).as(path).containsAll(dependency.dependsOn());
            }
        }
    }

    private static <T> T read(String resource, Class<T> type) {
        try (InputStream in = DemoEstateFixturesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("classpath resource %s", resource).isNotNull();
            return JSON.readValue(in, type);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
