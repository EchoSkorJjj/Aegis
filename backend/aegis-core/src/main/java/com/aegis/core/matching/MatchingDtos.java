package com.aegis.core.matching;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.signals.ChangeSignal;

/** Wire records for deterministic change-to-estate matching and its evidence chains. */
public final class MatchingDtos {

    private MatchingDtos() {
    }

    public record ChangeMatchResponse(
            ChangeRef change,
            int matchedComponentCount,
            int matchedVendorCount,
            List<ImpactedServiceMatch> impactedServices) {
    }

    public record ChangeRef(
            UUID id,
            ChangeSignal.SignalType signalType,
            String sourceId,
            String externalId,
            String title) {

        static ChangeRef from(ChangeSignal signal) {
            return new ChangeRef(
                    signal.id(), signal.signalType(), signal.sourceId(), signal.externalId(), signal.title());
        }
    }

    public record ImpactedServiceMatch(
            ServiceRef service, MatchConfidence confidence, List<OwnerRef> owners, List<EvidenceChain> evidence) {
    }

    /**
     * One complete, human-readable reason a change reaches a service. For an advisory,
     * {@code componentPath} runs from the vulnerable component to the component the service uses
     * directly. For an outage, {@code vendor} is populated and the component path is empty.
     */
    public record EvidenceChain(
            EvidenceKind kind,
            MatchConfidence confidence,
            String explanation,
            AffectedProductEvidence affectedProduct,
            List<ComponentRef> componentPath,
            VendorRef vendor) {
    }

    public enum EvidenceKind {
        ADVISORY_COMPONENT,
        VENDOR_DEPENDENCY
    }

    /**
     * How much to trust a match — the false-positive control. {@code CONFIRMED} is a fully-qualified
     * identity match whose version is provably in range; {@code POSSIBLE} is anything weaker (a bare
     * or vendor-aliased name that could collide across ecosystems, or a version that could not be
     * range-checked) and should be treated as "needs review", never auto-actioned.
     */
    public enum MatchConfidence {
        CONFIRMED,
        POSSIBLE
    }

    /** Whether a component's version could be placed in the advisory's affected range. */
    public enum VersionMatch {
        IN_RANGE,
        OUT_OF_RANGE,
        /** The version or a range bound was not comparable (unknown / non-SemVer) — surfaced, not dropped. */
        UNDETERMINED
    }

    public record AffectedProductEvidence(
            String cpe,
            String vendor,
            String product,
            String versionRange,
            IdentityMatch identityMatch,
            VersionMatch versionMatch) {

        static AffectedProductEvidence from(
                AffectedProduct affected, IdentityMatch identityMatch, VersionMatch versionMatch) {
            return new AffectedProductEvidence(
                    affected.cpe(), affected.vendor(), affected.product(), describeRange(affected),
                    identityMatch, versionMatch);
        }

        static String describeRange(AffectedProduct affected) {
            if (affected.affectsAllVersions()) {
                return "*";
            }
            if (affected.version() != null) {
                return "=" + affected.version();
            }
            String lower = affected.versionStartIncluding() != null
                    ? "[" + affected.versionStartIncluding()
                    : affected.versionStartExcluding() != null ? "(" + affected.versionStartExcluding() : "(-inf";
            String upper = affected.versionEndIncluding() != null
                    ? affected.versionEndIncluding() + "]"
                    : affected.versionEndExcluding() != null ? affected.versionEndExcluding() + ")" : "+inf)";
            return lower + "," + upper;
        }
    }

    /**
     * How a component's name was tied to the advisory's product.
     * <ul>
     *   <li>{@code EXACT_PACKAGE} — fully-qualified name, or a leaf name whose namespace confirms the
     *       CPE vendor. Strong.</li>
     *   <li>{@code CPE_VENDOR_PRODUCT_ALIAS} — a CPE product that is a prefix of the package leaf
     *       (e.g. CPE {@code log4j} vs Maven {@code log4j-core}), accepted only with the vendor
     *       present in the namespace. Weaker.</li>
     *   <li>{@code AMBIGUOUS_NAME} — a bare leaf name with no namespace or no CPE vendor to
     *       disambiguate; can collide across ecosystems/vendors. Weakest.</li>
     * </ul>
     */
    public enum IdentityMatch {
        EXACT_PACKAGE,
        CPE_VENDOR_PRODUCT_ALIAS,
        AMBIGUOUS_NAME
    }

    /**
     * A vulnerable component one service uses: directly ({@code via} null), or through the direct
     * dependency {@code via}. {@code manifestPaths} are the manifests declaring that direct dependency, and
     * {@code lockfilePaths} the JavaScript lockfiles resolving it, where the SBOM import recorded them.
     * {@code confidence} is CONFIRMED when any one vulnerability is.
     */
    public record VulnerableDependency(
            UUID serviceId,
            ComponentRef component,
            ComponentRef via,
            List<String> manifestPaths,
            List<String> lockfilePaths,
            MatchConfidence confidence,
            List<VulnerabilityRef> vulnerabilities) {

        public VulnerableDependency {
            manifestPaths = manifestPaths == null ? List.of() : List.copyOf(manifestPaths);
            lockfilePaths = lockfilePaths == null ? List.of() : List.copyOf(lockfilePaths);
        }

        /**
         * A package a lockfile resolves that none of the repository's manifests declares: linked directly, but
         * with lockfiles and no manifest. Like a transitive one, it is overridden rather than bumped.
         */
        public boolean undeclared() {
            return via == null && manifestPaths.isEmpty() && !lockfilePaths.isEmpty();
        }
    }

    /**
     * A vulnerability affecting a component: its canonical id (a CVE, or a GHSA with none), its change
     * signal, how firmly it matched, and the feeds' own severity — the highest CVSS base score published,
     * else a severity word.
     */
    public record VulnerabilityRef(
            String id,
            UUID changeSignalId,
            MatchConfidence confidence,
            String cvssVersion,
            BigDecimal cvssBaseScore,
            String severity) {
    }

    public record ComponentRef(UUID id, String ecosystem, String name, String version) {

        static ComponentRef from(SoftwareComponent component) {
            return new ComponentRef(
                    component.getId(), component.getEcosystem(), component.getName(), component.getVersion());
        }
    }

    /** Carries both scoring inputs the service itself owns — its business tier and its exposure. */
    public record ServiceRef(
            UUID id, String name, BusinessCriticality criticality, ServiceExposure exposure) {

        static ServiceRef from(BusinessService service) {
            return new ServiceRef(
                    service.getId(), service.getName(), service.getCriticality(), service.getExposure());
        }
    }

    /**
     * An impacted service's owner: {@code id} is the inventory owner row, {@code userId} the person's
     * identity-service id (what a signed-in user is matched against), {@code name} their display name.
     */
    public record OwnerRef(UUID id, UUID userId, String name) {

        static OwnerRef from(Owner owner) {
            return new OwnerRef(owner.getId(), owner.getUserId(), owner.getName());
        }
    }

    public record VendorRef(UUID id, String name) {

        static VendorRef from(ThirdPartyVendor vendor) {
            return new VendorRef(vendor.getId(), vendor.getName());
        }
    }
}
