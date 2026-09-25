package com.aegis.core.inventory.sbom;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The subset of a CycloneDX JSON document the inventory import reads; everything else is ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CycloneDxBom(
        String bomFormat, String specVersion, Metadata metadata, List<Component> components,
        List<Dependency> dependencies) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(Component component) {
    }

    /** {@code properties} are tool-specific name/value pairs — syft's say where it found the package. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Component(
            @JsonProperty("bom-ref") String ref, String type, String group, String name, String version,
            String purl, List<Component> components, List<Property> properties) {

        @JsonCreator
        public Component {
        }

        public Component(String ref, String type, String group, String name, String version, String purl,
                List<Component> components) {
            this(ref, type, group, name, version, purl, components, null);
        }

        /** The value of the first property named {@code name}, or null. */
        String property(String name) {
            if (properties == null) {
                return null;
            }
            return properties.stream().filter(property -> property != null && name.equals(property.name()))
                    .map(Property::value).findFirst().orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Property(String name, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dependency(String ref, List<String> dependsOn) {
    }
}
