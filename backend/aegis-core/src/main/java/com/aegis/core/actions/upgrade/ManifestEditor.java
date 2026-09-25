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

    /** What a reviewer must still do by hand after this edit (for example, refresh a lockfile). */
    default List<String> followUps() {
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
