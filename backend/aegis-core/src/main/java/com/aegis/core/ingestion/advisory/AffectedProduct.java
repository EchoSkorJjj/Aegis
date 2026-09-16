package com.aegis.core.ingestion.advisory;

/**
 * One affected product, normalised from a CPE match. {@code vendor} and {@code product} come from
 * the CPE 2.3 name; the version fields carry whichever form the feed used:
 *
 * <ul>
 *   <li>{@code version} — a single concrete version, when the CPE names one.</li>
 *   <li>the four {@code versionStart*}/{@code versionEnd*} bounds — an open or closed range.</li>
 *   <li>all six null — every version is affected (a wildcard CPE with no bounds).</li>
 * </ul>
 *
 * <p>Version strings are kept exactly as the feed spelled them. Comparing them against a registered
 * component's version is matching's job (ecosystem-specific ordering rules), not the connector's.
 */
public record AffectedProduct(
        String cpe,
        String part,
        String vendor,
        String product,
        String version,
        String versionStartIncluding,
        String versionStartExcluding,
        String versionEndIncluding,
        String versionEndExcluding) {

    /** True when no version constraint was given at all, i.e. every version of the product is affected. */
    public boolean affectsAllVersions() {
        return version == null
                && versionStartIncluding == null
                && versionStartExcluding == null
                && versionEndIncluding == null
                && versionEndExcluding == null;
    }
}
