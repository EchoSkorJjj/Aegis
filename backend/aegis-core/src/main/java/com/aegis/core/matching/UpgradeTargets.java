package com.aegis.core.matching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.matching.MatchingDtos.AffectedProductEvidence;
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.matching.MatchingDtos.IdentityMatch;
import com.aegis.core.matching.MatchingDtos.VersionMatch;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignalStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Picks the version to upgrade a vulnerable component to (E8 story 4): the lowest version that clears
 * the vulnerability being remediated and every other one Aegis knows a fix for, so fixing one CVE never
 * proposes a version another CVE still affects (Log4Shell's 2.15.0 was itself vulnerable).
 *
 * <p>Deterministic version math under matching's own rules — the same identity match and ecosystem
 * version schemes — never a guess. Starting from the installed version, it moves to the fixed version
 * ({@code versionEndExcluding}) of whichever range still contains the candidate, until none does:
 * <ul>
 *   <li>Every range of the <b>remediated</b> vulnerability must be cleared. One that publishes no fixed
 *       version, or that the candidate cannot be compared with, is a refusal that says why.</li>
 *   <li>A range of <b>another</b> vulnerability pushes the target up only when its identity match is
 *       strong (not a bare-name collision) and it publishes a fix. Anything else is reported in
 *       {@link UpgradeTarget#notCleared()} rather than blocking a fix the remediated CVE needs.</li>
 * </ul>
 * No advisory prose is read and no model is asked.
 */
@Service
public class UpgradeTargets {

    /** Bound on the move-to-the-fix walk; real advisory data settles in a handful of steps. */
    static final int MAX_STEPS = 32;

    private final ChangeSignalStore signals;
    private final AdvisoryStore advisories;

    public UpgradeTargets(ChangeSignalStore signals, AdvisoryStore advisories) {
        this.signals = signals;
        this.advisories = advisories;
    }

    /**
     * @param vulnerabilityId   the canonical id (e.g. a CVE) of the vulnerability being remediated
     * @param toVersion         the lowest version outside every range that must be cleared
     * @param checked           the canonical ids of every vulnerability whose ranges were considered
     * @param notCleared        other vulnerabilities whose ranges still contain, or cannot be compared
     *                          with, {@code toVersion} — no fix published, a weak identity match, or an
     *                          incomparable version — for a reviewer to look at
     */
    public record UpgradeTarget(
            String vulnerabilityId, String fromVersion, String toVersion, List<String> checked, List<String> notCleared) {

        public UpgradeTarget {
            checked = List.copyOf(checked);
            notCleared = List.copyOf(notCleared);
        }
    }

    /**
     * The higher of two versions of a package in {@code ecosystem}, by that ecosystem's ordering; {@code left} when
     * they can't be compared.
     */
    public static String higher(String ecosystem, String left, String right) {
        OptionalInt order = Ecosystems.versionScheme(ecosystem).compare(left, right);
        return order.isPresent() && order.getAsInt() < 0 ? right : left;
    }

    @Transactional(readOnly = true)
    public UpgradeTarget resolve(UUID changeSignalId, ComponentRef component) {
        ChangeSignal signal = signals.findById(changeSignalId)
                .orElseThrow(() -> new ApiExceptions.NotFound("Change signal not found: " + changeSignalId));
        if (signal.signalType() != ChangeSignal.SignalType.VULNERABILITY) {
            throw new ApiExceptions.BadRequest("Only a vulnerability can be remediated by an upgrade");
        }
        Advisory representative = advisories.findByExternalId(signal.sourceId(), signal.externalId())
                .orElseThrow(() -> new ApiExceptions.NotFound("Advisory backing change signal was not found"));
        String vulnerability = representative.canonicalId();

        // The remediated vulnerability's own feeds, plus every other advisory that could name the package.
        List<Advisory> known = new ArrayList<>(advisories.findByCanonicalId(vulnerability));
        known.addAll(advisories.findNamingPackage(packageKeys(component.name()), productKeys(component.name())));
        return resolve(vulnerability, component, known);
    }

    /**
     * {@link #resolve(UUID, ComponentRef)} from advisories already in hand, with no lookups — for working out
     * many targets at once ({@link VulnerableDependencies.Found#known}). {@code known} must hold what that
     * method loads: every advisory of {@code vulnerabilityId}, and every advisory the store's prefilter says
     * could name the package. Extra advisories are harmless; matching's identity rule drops them.
     */
    public static UpgradeTarget resolve(String vulnerabilityId, ComponentRef component, Collection<Advisory> known) {
        List<Range> ranges = known.stream()
                .flatMap(advisory -> advisory.affected().stream()
                        .filter(affected -> affected.part() == null || affected.part().equalsIgnoreCase("a"))
                        .map(affected -> Range.of(advisory.canonicalId(), component, affected)))
                .filter(range -> range != null)
                .distinct()
                .toList();
        return target(vulnerabilityId, component, ranges);
    }

    static UpgradeTarget target(String vulnerability, ComponentRef component, List<Range> ranges) {
        String current = component.version();
        if (ranges.stream().noneMatch(range -> range.vulnerability().equals(vulnerability)
                && placement(current, range) == VersionMatch.IN_RANGE)) {
            throw new ApiExceptions.Conflict(component.name() + " " + current + " is not provably inside a range "
                    + vulnerability + " publishes, so there is nothing to upgrade from");
        }
        String candidate = current;
        for (int step = 0; step < MAX_STEPS; step++) {
            Range containing = null;
            for (Range range : ranges) {
                boolean remediated = range.vulnerability().equals(vulnerability);
                if (!remediated && !(range.strong() && range.fixed() != null)) {
                    continue; // reported in notCleared; never blocks the fix the remediated CVE needs
                }
                VersionMatch placement = placement(candidate, range);
                if (placement == VersionMatch.UNDETERMINED && remediated) {
                    throw new ApiExceptions.Conflict("Version " + candidate + " of " + component.name()
                            + " cannot be compared with the range " + range.describe() + " of " + vulnerability
                            + ", so no upgrade can be proven to fix it");
                }
                if (placement == VersionMatch.IN_RANGE) {
                    containing = range;
                    break;
                }
            }
            if (containing == null) {
                return new UpgradeTarget(vulnerability, current, candidate, checked(ranges),
                        notCleared(vulnerability, candidate, ranges));
            }
            if (containing.fixed() == null) {
                throw new ApiExceptions.Conflict(vulnerability + " publishes no fixed version of " + component.name()
                        + " for the range " + containing.describe() + ", so there is no version to upgrade to");
            }
            candidate = containing.fixed();
        }
        throw new ApiExceptions.Conflict("No safe version of " + component.name() + " could be settled on");
    }

    private static VersionMatch placement(String version, Range range) {
        return VersionRangeMatcher.evaluate(version, range.affected(), Ecosystems.versionScheme(range.affected()));
    }

    private static List<String> checked(List<Range> ranges) {
        return ranges.stream().map(Range::vulnerability).distinct().sorted().toList();
    }

    private static List<String> notCleared(String vulnerability, String target, List<Range> ranges) {
        return ranges.stream()
                .filter(range -> !range.vulnerability().equals(vulnerability))
                .filter(range -> placement(target, range) != VersionMatch.OUT_OF_RANGE)
                .map(Range::vulnerability)
                .distinct()
                .sorted()
                .toList();
    }

    /** The purl key the store indexes: lower-cased, each run of {@code - _ .} folded to {@code -}. */
    static Set<String> packageKeys(String name) {
        return Set.of(name.trim().toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-"));
    }

    /**
     * Every CPE product the identity rule could accept for this package: the whole name, its leaf, and
     * each hyphen-prefix of the leaf (CPE {@code log4j} for Maven {@code log4j-core}), all canonical.
     */
    static Set<String> productKeys(String name) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(AdvisoryComponentMatcher.canonical(name));
        String leaf = AdvisoryComponentMatcher.canonical(AdvisoryComponentMatcher.leaf(name));
        for (int dash = leaf.indexOf('-'); dash > 0; dash = leaf.indexOf('-', dash + 1)) {
            keys.add(leaf.substring(0, dash));
        }
        keys.add(leaf);
        keys.remove("");
        return keys;
    }

    /** One affected range of one vulnerability, as it applies to the component being upgraded. */
    record Range(String vulnerability, AffectedProduct affected, boolean strong) {

        /** Null when matching's identity rule does not tie this affected product to the component. */
        static Range of(String vulnerability, ComponentRef component, AffectedProduct affected) {
            IdentityMatch identity = AdvisoryComponentMatcher.identityMatch(
                    component.ecosystem(), component.name(), affected);
            return identity == null ? null
                    : new Range(vulnerability, affected, identity != IdentityMatch.AMBIGUOUS_NAME);
        }

        /** The first fixed version, or null when the range publishes none (open-ended, inclusive or exact). */
        String fixed() {
            String end = affected.versionEndExcluding();
            return end == null || end.isBlank() ? null : end.trim();
        }

        String describe() {
            return AffectedProductEvidence.describeRange(affected);
        }
    }
}
