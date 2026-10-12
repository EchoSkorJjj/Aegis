package com.aegis.core.actions.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.aegis.core.actions.pr.FileChange;
import com.aegis.core.platform.common.ApiExceptions;

/**
 * Where an upgrade overrides a package the service's manifests don't declare — one another package pulls in — and in
 * which syntax. An override belongs to the project that resolves the package. For npm, that is the project of each
 * JavaScript lockfile resolving what pulls it in: npm and yarn read overrides from that project's package.json
 * ({@code overrides}, {@code resolutions}), pnpm from its pnpm-workspace.yaml. For Maven it is each pom.xml declaring
 * what pulls it in ({@code dependencyManagement}); for PyPI, each requirements.txt that does (a requirement of its
 * own). The recommendations and the plan both ask here, so they never disagree.
 */
final class Overrides {

    private Overrides() {
    }

    /** One file an override is written to, and for an npm package the package manager whose syntax it takes. */
    record Target(String manifestPath, String packageManager) {
    }

    /** The files to override in, or — when there are none — why, as a clause a person can act on. */
    record Found(List<Target> targets, String unavailable) {
    }

    /**
     * Where to override a package of {@code ecosystem} that comes in through a dependency declared in
     * {@code viaManifests} and resolved by {@code lockfiles}.
     */
    static Found find(String ecosystem, List<String> viaManifests, List<String> lockfiles) {
        Optional<ManifestEditor> editor = ManifestEditor.forEcosystem(ecosystem);
        if (editor.isEmpty()) {
            return new Found(List.of(), "Aegis drafts upgrades for Maven, npm and PyPI manifests");
        }
        if (!"npm".equals(ecosystem.trim().toLowerCase(Locale.ROOT))) {
            List<String> manifests = editor.get().manifestsFor(viaManifests);
            return manifests.isEmpty()
                    ? new Found(List.of(), "what pulls it in is declared only in " + String.join(", ", viaManifests)
                            + ", which Aegis doesn't edit")
                    : new Found(manifests.stream().map(manifest -> new Target(manifest, null)).toList(), null);
        }
        List<Target> targets = new ArrayList<>();
        for (String lockfile : lockfiles) {
            String path = lockfile == null ? "" : lockfile.strip();
            int slash = path.lastIndexOf('/');
            String directory = path.substring(0, slash + 1);
            String manager = switch (path.substring(slash + 1)) {
                case "pnpm-lock.yaml" -> "pnpm";
                case "yarn.lock" -> "yarn";
                case "package-lock.json", "npm-shrinkwrap.json" -> "npm";
                default -> null;
            };
            if (manager == null) {
                continue;
            }
            String file = directory + (manager.equals("pnpm") ? "pnpm-workspace.yaml" : "package.json");
            try {
                FileChange.requireSafePath(file);
            } catch (ApiExceptions.BadRequest unsafe) {
                continue;
            }
            Target target = new Target(file, manager);
            if (!targets.contains(target)) {
                targets.add(target);
            }
        }
        return targets.isEmpty()
                ? new Found(List.of(), "re-import the service's SBOM from its repository so Aegis can tell which "
                        + "lockfile resolves it and override it there")
                : new Found(List.copyOf(targets), null);
    }

    /** Why a package that comes in through {@code via} (null: through nothing declared) isn't overridden. */
    static String notOverridden(String name, String version, String via, String unavailable, String toVersion) {
        String pin = "pin " + name + (toVersion == null ? "" : " to " + toVersion) + " by hand";
        return (version == null ? name : name + " " + version)
                + (via == null ? " is resolved by a lockfile though no manifest declares it" : " comes in through " + via)
                + "; " + unavailable + ", or " + (via == null ? "" : "upgrade " + via + " or ") + pin;
    }
}
