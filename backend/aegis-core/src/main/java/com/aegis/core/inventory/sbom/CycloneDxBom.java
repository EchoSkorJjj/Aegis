package com.aegis.core.inventory.sbom;

import java.util.List;

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Component(
            @JsonProperty("bom-ref") String ref, String type, String group, String name, String version,
            String purl, List<Component> components) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dependency(String ref, List<String> dependsOn) {
    }
}
