package com.aegis.core.matching;

import java.util.Locale;

import com.aegis.core.ingestion.advisory.AffectedProduct;

/**
 * Reconciles the ecosystem names OSV, purl, and inventory components use (OSV "PyPI" vs purl "pypi",
 * OSV "crates.io" vs purl "cargo", …) and selects each ecosystem's version-ordering scheme. Central
 * so identity matching and version comparison agree on what "the same ecosystem" means.
 */
final class Ecosystems {

    private static final VersionScheme SEMVER = new SemverScheme();
    private static final VersionScheme PEP440 = new Pep440Scheme();
    private static final VersionScheme TOKEN = new TokenScheme();

    private Ecosystems() {
    }

    enum Ecosystem {
        NPM,
        PYPI,
        CARGO,
        RUBYGEMS,
        GO,
        MAVEN,
        GENERIC
    }

    static Ecosystem canonical(String raw) {
        if (raw == null) {
            return Ecosystem.GENERIC;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "npm" -> Ecosystem.NPM;
            case "pypi", "pip", "python" -> Ecosystem.PYPI;
            case "cargo", "crates.io", "crates", "rust" -> Ecosystem.CARGO;
            case "gem", "rubygems", "ruby" -> Ecosystem.RUBYGEMS;
            case "go", "golang" -> Ecosystem.GO;
            case "maven", "mvn", "java" -> Ecosystem.MAVEN;
            default -> Ecosystem.GENERIC;
        };
    }

    /** True when two ecosystem labels name the same registry (e.g. OSV "PyPI" and purl "pypi"). */
    static boolean sameEcosystem(String left, String right) {
        Ecosystem canonical = canonical(left);
        return canonical != Ecosystem.GENERIC && canonical == canonical(right);
    }

    /**
     * The scheme to compare a component's version against an affected product's range bounds: the
     * ecosystem's own scheme for a purl advisory, and SemVer (the historical default) for a CPE one.
     */
    static VersionScheme versionScheme(AffectedProduct affected) {
        return affected.isPackage() ? versionScheme(affected.ecosystem()) : SEMVER;
    }

    static VersionScheme versionScheme(String ecosystem) {
        return switch (canonical(ecosystem)) {
            case NPM, CARGO, GO -> SEMVER;
            case PYPI -> PEP440;
            default -> TOKEN; // RubyGems, Maven, and unrecognised ecosystems
        };
    }

    /** Canonical package name for identity matching (case-folded; PyPI normalised per PEP 503). */
    static String normalizePackage(String ecosystem, String name) {
        if (name == null) {
            return "";
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return canonical(ecosystem) == Ecosystem.PYPI ? normalized.replaceAll("[-_.]+", "-") : normalized;
    }
}
