package com.aegis.core.actions.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.aegis.core.actions.pr.FileChange;
import com.aegis.core.platform.common.ApiExceptions;

/**
 * Rewrites one kind of dependency manifest so a single package moves from one version to another.
 * Deterministic text edits that leave the rest of the file byte-for-byte alone, so the pull request's
 * diff is just the version. An edit that cannot be made safely — the package is transitive, managed
 * elsewhere, or declared in a form the editor does not understand — is refused with the reason
 * ({@code ApiExceptions.Conflict}), never approximated.
 */
interface ManifestEditor {

    /** The manifest's file name; a custom manifest path must end in it. */
    String fileName();

    /**
     * The manifests an upgrade edits, given the files the SBOM import recorded the dependency in: those this editor
     * edits; the root manifest when nothing was recorded (an uploaded SBOM, a component linked by hand); none when
     * it was recorded only in files this editor doesn't edit — a Gradle lockfile, {@code poetry.lock}, a
     * {@code requirements-dev.txt}, a jar — since guessing the root manifest there opens a pull request that fails.
     */
    default List<String> manifestsFor(List<String> recorded) {
        List<String> usable = new ArrayList<>();
        for (String path : recorded) {
            String trimmed = path == null ? "" : path.strip();
            try {
                FileChange.requireSafePath(trimmed);
            } catch (ApiExceptions.BadRequest unsafe) {
                continue;
            }
            if ((trimmed.equals(fileName()) || trimmed.endsWith("/" + fileName())) && !usable.contains(trimmed)) {
                usable.add(trimmed);
            }
        }
        return usable.isEmpty() && recorded.isEmpty() ? List.of(fileName()) : usable;
    }

    /** The rewritten manifest. Throws {@code ApiExceptions.Conflict} naming why when it cannot be. */
    String upgrade(String manifest, String packageName, String fromVersion, String toVersion);

    /**
     * What someone must still do by hand, on the pull request's branch, once these manifests are edited — refresh
     * the lockfile beside each, say — as Markdown for the pull request's checklist. Empty when the edits are
     * complete on their own.
     */
    default List<String> followUps(List<String> manifestPaths) {
        return List.of();
    }

    /** The editor for a component's ecosystem, if automated upgrades support it. */
    static Optional<ManifestEditor> forEcosystem(String ecosystem) {
        if (ecosystem == null) {
            return Optional.empty();
        }
        return switch (ecosystem.trim().toLowerCase(Locale.ROOT)) {
            case "maven" -> Optional.of(new MavenPomEditor());
            case "npm" -> Optional.of(new NpmPackageJsonEditor());
            case "pypi", "pip" -> Optional.of(new PipRequirementsEditor());
            default -> Optional.empty();
        };
    }
}
