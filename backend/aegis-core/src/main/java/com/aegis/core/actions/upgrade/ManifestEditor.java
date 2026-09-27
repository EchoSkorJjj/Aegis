package com.aegis.core.actions.upgrade;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
