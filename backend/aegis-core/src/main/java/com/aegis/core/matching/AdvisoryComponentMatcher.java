package com.aegis.core.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AffectedProduct;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.matching.MatchingDtos.IdentityMatch;
import org.springframework.stereotype.Component;

/** Exact, rule-based advisory identity and version matching. No feed text or AI is involved. */
@Component
class AdvisoryComponentMatcher {

    List<ComponentMatch> match(Advisory advisory, List<SoftwareComponent> components) {
        List<ComponentMatch> matches = new ArrayList<>();
        for (AffectedProduct affected : advisory.affected()) {
            if (affected.part() != null && !affected.part().equalsIgnoreCase("a")) {
                continue;
            }
            for (SoftwareComponent component : components) {
                IdentityMatch identity = identityMatch(component.getName(), affected);
                if (identity != null && VersionRangeMatcher.matches(component.getVersion(), affected)) {
                    matches.add(new ComponentMatch(component, affected, identity));
                }
            }
        }
        return List.copyOf(matches);
    }

    private static IdentityMatch identityMatch(String packageName, AffectedProduct affected) {
        String product = canonical(affected.product());
        if (product.isEmpty()) {
            return null;
        }
        String whole = canonical(packageName);
        String leaf = canonical(leaf(packageName));
        if (product.equals(whole)) {
            return IdentityMatch.EXACT_PACKAGE;
        }
        String namespace = canonical(namespace(packageName));
        String vendor = canonical(affected.vendor());
        boolean vendorInNamespace = !vendor.isEmpty()
                && (namespace.equals(vendor)
                        || namespace.startsWith(vendor + "-")
                        || namespace.endsWith("-" + vendor)
                        || namespace.contains("-" + vendor + "-"));
        if (product.equals(leaf) && (namespace.isEmpty() || vendor.isEmpty() || vendorInNamespace)) {
            return IdentityMatch.EXACT_PACKAGE;
        }

        // CPE product names sometimes omit a package suffix (for example CPE "log4j" versus
        // Maven artifact "log4j-core"). Accept that alias only when the CPE vendor is also present
        // in the package namespace; this prevents a product-prefix-only cross-vendor match.
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
            SoftwareComponent component, AffectedProduct affectedProduct, IdentityMatch identityMatch) {
    }

}
