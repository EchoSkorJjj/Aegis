package com.aegis.core.actions.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.aegis.core.actions.pr.FileChange;
import com.aegis.core.actions.upgrade.UpgradeProposal.Edit;
import com.aegis.core.platform.common.ApiExceptions;

/**
 * Rewrites one kind of dependency manifest so a single package moves from one version to another.
 * Deterministic text edits that leave the rest of the file byte-for-byte alone, so the pull request's
 * diff is just the version. A package the manifest declares has its declaration bumped ({@link #upgrade}); one it
 * doesn't, which another package pulls in, is overridden for the whole project ({@link #override}). An edit that
 * cannot be made safely — managed elsewhere, or declared in a form the editor does not understand — is refused with
 * the reason ({@code ApiExceptions.Conflict}), never approximated.
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

    /** The rewritten manifest, its declaration of the package bumped. Throws {@code ApiExceptions.Conflict} naming why when it cannot be. */
    String upgrade(String manifest, String packageName, String fromVersion, String toVersion);

    /**
     * The rewritten manifest, forcing {@code packageName} to {@code toVersion} wherever the project's dependencies pull
     * it in, though the manifest declares no dependency on it. {@code via} names what pulls it in, or is null. Throws
     * {@code ApiExceptions.Conflict} naming why when it cannot.
     */
    default String override(String manifest, String packageName, String fromVersion, String toVersion, String via) {
        throw new ApiExceptions.Conflict(fileName() + " cannot override " + packageName + "; upgrade it by hand");
    }

    /**
     * The manifest with an override of {@code packageName} it already has raised to {@code toVersion}, because the
     * package's declaration moves there elsewhere in the pull request: an override left behind keeps the old version
     * (and npm refuses one that differs from the project's own declaration). Unchanged when it has none.
     */
    default String raiseOverride(String manifest, String packageName, String toVersion) {
        return manifest;
    }

    /** Whether a missing manifest is created rather than refused: an override can be its first setting. */
    default boolean createsMissing() {
        return false;
    }

    /** One edit, applied: its override when it has one, an existing override raised when it only follows a bump, else its declaration bumped. */
    default String apply(String manifest, Edit edit) {
        if (edit.override() == null) {
            return upgrade(manifest, edit.packageName(), edit.fromVersion(), edit.toVersion());
        }
        return edit.follows() ? raiseOverride(manifest, edit.packageName(), edit.toVersion())
                : override(manifest, edit.packageName(), edit.fromVersion(), edit.toVersion(), edit.override().via());
    }

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

    /**
     * The editor that applies one edit: its ecosystem's, except that an npm package's override takes its package
     * manager's file and syntax — npm's {@code overrides} and yarn's {@code resolutions} in package.json, pnpm's
     * {@code overrides} in pnpm-workspace.yaml.
     */
    static ManifestEditor forEdit(Edit edit) {
        if (edit.override() != null && "npm".equalsIgnoreCase(edit.ecosystem() == null ? "" : edit.ecosystem().trim())) {
            String manager = edit.override().packageManager() == null ? "npm"
                    : edit.override().packageManager().toLowerCase(Locale.ROOT);
            return switch (manager) {
                case "pnpm" -> new PnpmWorkspaceEditor();
                case "yarn" -> NpmPackageJsonEditor.yarn();
                default -> new NpmPackageJsonEditor();
            };
        }
        return forEcosystem(edit.ecosystem())
                .orElseThrow(() -> new ApiExceptions.Conflict("No manifest editor for " + edit.ecosystem()));
    }
}
