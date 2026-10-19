package com.aegis.core.advisories;

/**
 * One affected product, either CPE-shaped (from NVD) or purl-shaped (from OSV).
 *
 * <ul>
 *   <li>CPE feeds set {@code cpe} / {@code vendor} / {@code product} (the CPE 2.3 name); ecosystem and
 *       package are null.</li>
 *   <li>Ecosystem-native feeds set {@code ecosystem} (e.g. {@code npm}, {@code PyPI}) and
 *       {@code packageName}; cpe / vendor / product are null.</li>
 * </ul>
 *
 * <p>The version fields are shared by both shapes:
 * <ul>
 *   <li>{@code version} — a single concrete version.</li>
 *   <li>the four {@code versionStart*}/{@code versionEnd*} bounds — an open or closed range.</li>
 *   <li>all five null — every version is affected.</li>
 * </ul>
 *
 * <p>Version strings are kept exactly as the feed spelled them; comparing them against a component's
 * version (with ecosystem-specific ordering) is matching's job, not the connector's.
 */
public record AffectedProduct(
        String cpe,
        String part,
        String vendor,
        String product,
        String ecosystem,
        String packageName,
        String version,
        String versionStartIncluding,
        String versionStartExcluding,
        String versionEndIncluding,
        String versionEndExcluding) {

    /** CPE-shaped affected product (NVD). Ecosystem and package are absent. */
    public AffectedProduct(
            String cpe,
            String part,
            String vendor,
            String product,
            String version,
            String versionStartIncluding,
            String versionStartExcluding,
            String versionEndIncluding,
            String versionEndExcluding) {
        this(cpe, part, vendor, product, null, null,
                version, versionStartIncluding, versionStartExcluding, versionEndIncluding, versionEndExcluding);
    }

    /** Purl-shaped affected product (OSV). Vendor/product/CPE are absent; part is always "a". */
    public static AffectedProduct ofPackage(
            String ecosystem,
            String packageName,
            String version,
            String versionStartIncluding,
            String versionStartExcluding,
            String versionEndIncluding,
            String versionEndExcluding) {
        return new AffectedProduct(null, "a", null, null, ecosystem, packageName,
                version, versionStartIncluding, versionStartExcluding, versionEndIncluding, versionEndExcluding);
    }

    /** True when this affected product identifies an ecosystem package (purl), rather than a CPE. */
    public boolean isPackage() {
        return ecosystem != null && !ecosystem.isBlank() && packageName != null && !packageName.isBlank();
    }

    /** True when no version constraint was given at all, i.e. every version of the product is affected. */
    public boolean affectsAllVersions() {
        return version == null
                && versionStartIncluding == null
                && versionStartExcluding == null
                && versionEndIncluding == null
                && versionEndExcluding == null;
    }
}
