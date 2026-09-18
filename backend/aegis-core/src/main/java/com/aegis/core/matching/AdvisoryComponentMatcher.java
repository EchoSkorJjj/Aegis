package com.aegis.core.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AffectedProduct;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.matching.MatchingDtos.IdentityMatch;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.VersionMatch;
import org.springframework.stereotype.Component;

/** Exact, rule-based advisory identity and version matching. No feed text or AI is involved. */
@Component
class AdvisoryComponentMatcher {

    List<ComponentMatch> match(Advisory advisory, List<SoftwareComponent> components) {
        return match(advisory.affected(), components);
    }

    /** Match against a flat set of affected products — the union across a vulnerability's feeds. */
    List<ComponentMatch> match(List<AffectedProduct> affectedProducts, List<SoftwareComponent> components) {
        List<ComponentMatch> matches = new ArrayList<>();
        for (AffectedProduct affected : affectedProducts) {
            if (affected.part() != null && !affected.part().equalsIgnoreCase("a")) {
                continue;
            }
            for (SoftwareComponent component : components) {
                IdentityMatch identity = identityMatch(component, affected);
                if (identity == null) {
                    continue;
                }
                VersionMatch versionMatch = VersionRangeMatcher.evaluate(
                        component.getVersion(), affected, Ecosystems.versionScheme(affected));
                // A version we can prove is outside the range is the only hard exclusion. IN_RANGE and
                // UNDETERMINED both survive — the latter as a review item, never silently dropped.
                if (versionMatch == VersionMatch.OUT_OF_RANGE) {
                    continue;
                }
                matches.add(new ComponentMatch(component, affected, identity, versionMatch,
                        confidenceOf(identity, versionMatch)));
            }
        }
        return List.copyOf(matches);
    }

    /** CONFIRMED only when the identity is fully qualified and the version is provably in range. */
    private static MatchConfidence confidenceOf(IdentityMatch identity, VersionMatch versionMatch) {
        return identity == IdentityMatch.EXACT_PACKAGE && versionMatch == VersionMatch.IN_RANGE
                ? MatchConfidence.CONFIRMED
                : MatchConfidence.POSSIBLE;
    }

    /**
     * Purl advisories (OSV) match by ecosystem + exact package name — unambiguous, so no cross-ecosystem
     * false positives. CPE advisories (NVD) fall back to the vendor/product/namespace heuristic.
     */
    private static IdentityMatch identityMatch(SoftwareComponent component, AffectedProduct affected) {
        if (affected.isPackage()) {
            boolean samePackage = Ecosystems.sameEcosystem(component.getEcosystem(), affected.ecosystem())
                    && Ecosystems.normalizePackage(affected.ecosystem(), component.getName())
                            .equals(Ecosystems.normalizePackage(affected.ecosystem(), affected.packageName()));
            return samePackage ? IdentityMatch.EXACT_PACKAGE : null;
        }
        return cpeIdentity(component.getName(), affected);
    }

    private static IdentityMatch cpeIdentity(String packageName, AffectedProduct affected) {
        String product = canonical(affected.product());
        if (product.isEmpty()) {
            return null;
        }
        String leaf = canonical(leaf(packageName));
        String namespace = canonical(namespace(packageName));

        if (namespace.isEmpty()) {
            // Bare name: nothing qualifies the ecosystem or vendor, so even a full-name match is
            // reviewable — the same short name lives in many ecosystems (npm vs PyPI "requests").
            return product.equals(leaf) ? IdentityMatch.AMBIGUOUS_NAME : null;
        }

        String whole = canonical(packageName);
        if (product.equals(whole)) {
            // The CPE spells out the full, qualified package name — as strong as it gets.
            return IdentityMatch.EXACT_PACKAGE;
        }

        String vendor = canonical(affected.vendor());
        boolean vendorInNamespace = !vendor.isEmpty()
                && (namespace.equals(vendor)
                        || namespace.startsWith(vendor + "-")
                        || namespace.endsWith("-" + vendor)
                        || namespace.contains("-" + vendor + "-"));

        if (product.equals(leaf)) {
            if (vendor.isEmpty()) {
                // A namespaced leaf match with no CPE vendor to confirm it — reviewable, not exact.
                return IdentityMatch.AMBIGUOUS_NAME;
            }
            // Require the CPE vendor to appear in the namespace; rejects a same-leaf other vendor.
            return vendorInNamespace ? IdentityMatch.EXACT_PACKAGE : null;
        }

        // CPE product names sometimes omit a package suffix (for example CPE "log4j" versus Maven
        // artifact "log4j-core"). Accept that alias only when the CPE vendor is also present in the
        // package namespace; this prevents a product-prefix-only cross-vendor match.
        boolean productPrefix = leaf.startsWith(product + "-");
        return productPrefix && vendorInNamespace ? IdentityMatch.CPE_VENDOR_PRODUCT_ALIAS : null;
    }

    private static String leaf(String value) {
        int slash = value.lastIndexOf('/');
        int colon = value.lastIndexOf(':');
        return value.substring(Math.max(slash, colon) + 1);
    }

    private static String namespace(String value) {
        int slash = value.lastIndexOf('/');
        int colon = value.lastIndexOf(':');
        int end = Math.max(slash, colon);
        return end < 0 ? "" : value.substring(0, end);
    }

    private static String canonical(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    record ComponentMatch(
            SoftwareComponent component,
            AffectedProduct affectedProduct,
            IdentityMatch identityMatch,
            VersionMatch versionMatch,
            MatchConfidence confidence) {
    }

}
