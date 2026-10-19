package com.aegis.core.ingestion.connectors.nvd;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The four CPE 2.3 fields the pipeline uses, parsed from a formatted CPE string
 * ({@code cpe:2.3:part:vendor:product:version:update:edition:language:sw_edition:target_sw:target_hw:other}).
 *
 * <p>Parsing is escape-aware: CPE quotes special characters with a backslash, so a product named
 * {@code log4j\:core} contains a literal colon that must not split the name. The two special values
 * are {@code *} (ANY) and {@code -} (NA); both become {@code null} here, since neither names a
 * concrete version.
 */
record CpeName(String part, String vendor, String product, String version) {

    private static final String PREFIX = "cpe:2.3:";

    /** {@code cpe}, {@code 2.3}, then the eleven CPE fields. */
    private static final int COMPONENT_COUNT = 13;

    /**
     * Parse a formatted CPE 2.3 string. Empty when it is malformed, or when it names no concrete
     * vendor and product — such a CPE cannot identify a product, so it is useless for matching.
     */
    static Optional<CpeName> parse(String cpe) {
        if (cpe == null || !cpe.startsWith(PREFIX)) {
            return Optional.empty();
        }
        List<String> components = split(cpe);
        if (components.size() != COMPONENT_COUNT) {
            return Optional.empty();
        }
        String vendor = concrete(components.get(3));
        String product = concrete(components.get(4));
        if (vendor == null || product == null) {
            return Optional.empty();
        }
        return Optional.of(new CpeName(concrete(components.get(2)), vendor, product, concrete(components.get(5))));
    }

    /** Split on unescaped colons, dropping the backslash from each escaped character. */
    private static List<String> split(String value) {
        List<String> components = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == ':') {
                components.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        components.add(current.toString());
        return components;
    }

    private static String concrete(String value) {
        return value == null || value.isEmpty() || "*".equals(value) || "-".equals(value) ? null : value;
    }
}
