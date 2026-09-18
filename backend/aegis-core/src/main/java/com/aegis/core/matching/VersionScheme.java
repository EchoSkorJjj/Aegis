package com.aegis.core.matching;

import java.util.OptionalInt;

/**
 * Compares two version strings using one ecosystem's ordering rules (SemVer, PEP 440, …).
 *
 * <p>Returns the sign of {@code left} relative to {@code right} (negative when {@code left} is lower),
 * or {@link OptionalInt#empty()} when either string is not a valid version in this scheme — which the
 * range matcher surfaces as {@code UNDETERMINED} rather than a silent miss.
 */
interface VersionScheme {

    OptionalInt compare(String left, String right);
}
