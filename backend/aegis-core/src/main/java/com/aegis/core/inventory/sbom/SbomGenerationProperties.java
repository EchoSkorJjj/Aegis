package com.aegis.core.inventory.sbom;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for generating a CycloneDX SBOM from a source repository ({@code aegis.inventory.sbom-generation.*}).
 *
 * <p>The generator shallow-clones a repository and runs <b>syft</b> over the checkout. Syft is a
 * <i>static</i> cataloguer — it reads lockfiles and manifests and never runs a package manager or any
 * of the repository's own code — so the attack surface is the clone and the file walk, not code
 * execution. Even so this shells out to external binaries and reaches the network, so it is bounded on
 * every axis (host allow-list, clone/scan timeouts, repo and output size caps) and can be disabled
 * wholesale. Requires {@code git} and {@code syft} on the host/image (the Docker image installs both).
 */
@ConfigurationProperties("aegis.inventory.sbom-generation")
public record SbomGenerationProperties(
        boolean enabled,
        List<String> allowedHosts,
        String gitPath,
        String syftPath,
        Duration cloneTimeout,
        Duration scanTimeout,
        long maxRepoBytes,
        long maxSbomBytes) {

    public SbomGenerationProperties {
        // Only these hosts may be cloned — never an internal or arbitrary host. The common public git
        // hosts by default; add a self-hosted GitLab/Gitea/etc. host here to allow it.
        allowedHosts = allowedHosts == null || allowedHosts.isEmpty()
                ? List.of("github.com", "gitlab.com", "bitbucket.org")
                : List.copyOf(allowedHosts);
        if (gitPath == null || gitPath.isBlank()) {
            gitPath = "git";
        }
        if (syftPath == null || syftPath.isBlank()) {
            syftPath = "syft";
        }
        if (cloneTimeout == null || cloneTimeout.isNegative() || cloneTimeout.isZero()) {
            cloneTimeout = Duration.ofSeconds(90);
        }
        if (scanTimeout == null || scanTimeout.isNegative() || scanTimeout.isZero()) {
            scanTimeout = Duration.ofSeconds(180);
        }
        // A shallow clone of a source tree; reject anything that balloons past this (a runaway or a
        // repo padded with binaries) before syft ever walks it.
        if (maxRepoBytes < 1) {
            maxRepoBytes = 512L * 1024 * 1024;
        }
        // Untrusted generated document: cap the SBOM syft emits before it is parsed into memory.
        if (maxSbomBytes < 1) {
            maxSbomBytes = 64L * 1024 * 1024;
        }
    }
}
