package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.ChangeMatchResponse;
import com.aegis.core.matching.MatchingDtos.ChangeRef;
import com.aegis.core.matching.MatchingDtos.ImpactedServiceMatch;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.matching.MatchingService;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoreFactor;
import com.aegis.core.scoring.ScoringDtos.ScoreFactorKind;
import com.aegis.core.scoring.ScoringDtos.ScoredOwner;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.scoring.ScoringDtos.SeveritySource;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignal.SignalType;
import com.aegis.core.signals.ChangeSignalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock
    private MatchingService matching;

    @Mock
    private ChangeSignalStore signals;

    @Mock
    private AdvisoryStore advisories;

    private ScoringService scoring;

    @BeforeEach
    void setUp() {
        scoring = new ScoringService(
                matching, signals, advisories, new ImpactScorer(ScoringProperties.defaults()));
    }

    @Test
    void scoresEachImpactedServiceFromTheAdvisorysCvssAndTheServicesOwnAttributes() {
        UUID signalId = UUID.randomUUID();
        ChangeSignal signal = signal(signalId, SignalType.VULNERABILITY, "CRITICAL");
        ServiceRef payments = service("Payments API", BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
        ServiceRef reporting = service("Internal Reporting", BusinessCriticality.LOW, ServiceExposure.ISOLATED);

        stubSignal(signal);
        stubAdvisory(signal, cvss("3.1", "10.0"));
        stubMatch(signalId, signal, impacted(payments, MatchConfidence.CONFIRMED), impacted(reporting));

        ChangeImpactResponse response = scoring.scoreChange(signalId);

        assertThat(response.severity().source()).isEqualTo(SeveritySource.CVSS);
        assertThat(response.severity().factor()).isEqualTo(1.0);
        // Highest score first — the response is a work queue, not the matcher's alphabetical order.
        assertThat(response.services()).extracting(scored -> scored.service().name())
                .containsExactly("Payments API", "Internal Reporting");

        ScoredService worst = response.services().getFirst();
        assertThat(worst.score()).isEqualTo(100.0);
        assertThat(worst.tier()).isEqualTo(ImpactTier.CRITICAL);
        assertThat(worst.material()).isTrue();
        assertThat(worst.breakdown().exposure()).isEqualTo(ServiceExposure.INTERNET_FACING);
        assertThat(worst.breakdown().criticality()).isEqualTo(BusinessCriticality.CRITICAL);
        assertThat(worst.explanation())
                .isEqualTo("CVE-2099-1000 scores 100.0 (CRITICAL) for Payments API: severity 1.00 "
                        + "(CVSS 3.1 base 10) x exposure 1.00 (INTERNET_FACING) x criticality 1.00 (CRITICAL)");

        ScoredService least = response.services().getLast();
        assertThat(least.score()).isEqualTo(12.5);
        assertThat(least.tier()).isEqualTo(ImpactTier.LOW);
    }

    @Test
    void surfacesEachContributingFactorAndWhatHoldsTheScoreDown() {
        UUID signalId = UUID.randomUUID();
        ChangeSignal signal = signal(signalId, SignalType.VULNERABILITY, null);

        stubSignal(signal);
        stubAdvisory(signal, cvss("3.1", "10.0"));
        stubMatch(signalId, signal,
                impacted(service("Internal Reporting", BusinessCriticality.LOW, ServiceExposure.ISOLATED)));

        ScoredService scored = scoring.scoreChange(signalId).services().getFirst();

        // 1.0 x 0.5 (ISOLATED) x 0.25 (LOW) = 12.5. Were it CRITICAL it would score 50.0, were it
        // internet-facing 25.0 — so the business tier, not the exposure, is what keeps it low.
        assertThat(scored.score()).isEqualTo(12.5);
        assertThat(scored.factors()).extracting(ScoreFactor::factor).containsExactly(
                ScoreFactorKind.SEVERITY, ScoreFactorKind.EXPOSURE,
                ScoreFactorKind.CRITICALITY, ScoreFactorKind.CONFIDENCE);
        assertThat(scored.factors()).extracting(ScoreFactor::input)
                .containsExactly("CVSS 3.1 base 10", "ISOLATED", "LOW", "CONFIRMED");
        assertThat(scored.factors()).extracting(ScoreFactor::headroom).containsExactly(0.0, 12.5, 37.5, 0.0);
        assertThat(scored.factors()).filteredOn(ScoreFactor::limiting)
                .extracting(ScoreFactor::factor)
                .containsExactly(ScoreFactorKind.CRITICALITY);
        assertThat(scored.explanation())
                .endsWith("; criticality (LOW) holds it down most, costing 37.5 points");
    }

    @Test
    void takesTheHighestCvssAcrossEveryFeedReportingTheSameVulnerability() {
        UUID signalId = UUID.randomUUID();
        ChangeSignal signal = signal(signalId, SignalType.VULNERABILITY, null);
        Advisory nvd = advisory("nvd", signal.externalId(), cvss("3.1", "6.5"));
        Advisory osv = advisory("osv", "GHSA-xxxx", cvss("3.1", "9.8"));

        stubSignal(signal);
        when(advisories.findByExternalId("nvd", signal.externalId())).thenReturn(Optional.of(nvd));
        when(advisories.findByCanonicalId(signal.externalId())).thenReturn(List.of(nvd, osv));
        stubMatch(signalId, signal,
                impacted(service("Orders", BusinessCriticality.HIGH, ServiceExposure.INTERNAL)));

        ChangeImpactResponse response = scoring.scoreChange(signalId);

        // Under-scoring because one feed lagged is the failure that matters, so the worst score wins.
        assertThat(response.severity().cvssBaseScore()).isEqualByComparingTo("9.8");
        // 0.98 x 0.9 (INTERNAL) x 0.8 (HIGH) = 70.56 -> 70.6
        assertThat(response.services().getFirst().score()).isEqualTo(70.6);
    }

    @Test
    void fallsBackToTheSignalsSeverityLabelWhenNoFeedHasScoredTheCveYet() {
        UUID signalId = UUID.randomUUID();
        ChangeSignal signal = signal(signalId, SignalType.VULNERABILITY, "HIGH");
        Advisory unscored = advisory("nvd", signal.externalId(), null);

        stubSignal(signal);
        when(advisories.findByExternalId("nvd", signal.externalId())).thenReturn(Optional.of(unscored));
        when(advisories.findByCanonicalId(signal.externalId())).thenReturn(List.of(unscored));
        stubMatch(signalId, signal,
                impacted(service("Orders", BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING)));

        ChangeImpactResponse response = scoring.scoreChange(signalId);

        assertThat(response.severity().source()).isEqualTo(SeveritySource.SEVERITY_LABEL);
        assertThat(response.severity().label()).isEqualTo("HIGH");
        assertThat(response.services().getFirst().score()).isEqualTo(75.0);
    }

    @Test
    void scoresAVendorOutageFromTheVendorsOwnImpactVocabulary() {
        UUID signalId = UUID.randomUUID();
        ChangeSignal signal = new ChangeSignal(
                signalId, SignalType.VENDOR_OUTAGE, "vendor-status", "acme:1", "Acme degraded",
                null, "major", null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);

        stubSignal(signal);
        // Isolated on purpose: an outage is not an attack, so the service's exposure must not discount it.
        stubMatch(signalId, signal,
                impacted(service("Checkout", BusinessCriticality.CRITICAL, ServiceExposure.ISOLATED)));

        ChangeImpactResponse response = scoring.scoreChange(signalId);

        // No advisory is consulted at all for an outage — there is no CVSS to look up.
        assertThat(response.severity().source()).isEqualTo(SeveritySource.SEVERITY_LABEL);
        assertThat(response.severity().factor()).isEqualTo(0.75);
        ScoredService checkout = response.services().getFirst();
        assertThat(checkout.score()).isEqualTo(75.0);
        assertThat(checkout.breakdown().exposure()).isEqualTo(ServiceExposure.ISOLATED);
        assertThat(checkout.breakdown().exposureApplies()).isFalse();
        assertThat(checkout.breakdown().exposureFactor()).isEqualTo(1.0);
    }

    @Test
    void rollsUpToAnOwnersWorstServiceRatherThanTheSumOfTheirServices() {
        UUID signalId = UUID.randomUUID();
        ChangeSignal signal = signal(signalId, SignalType.VULNERABILITY, null);
        OwnerRef web = new OwnerRef(UUID.randomUUID(), UUID.randomUUID(), "Web Platform Team");
        OwnerRef data = new OwnerRef(UUID.randomUUID(), UUID.randomUUID(), "Data Team");
        ServiceRef portal = service("Customer Portal", BusinessCriticality.HIGH, ServiceExposure.INTERNET_FACING);
        ServiceRef marketing = service("Marketing Site", BusinessCriticality.MEDIUM, ServiceExposure.INTERNET_FACING);
        ServiceRef reporting = service("Internal Reporting", BusinessCriticality.LOW, ServiceExposure.ISOLATED);

        stubSignal(signal);
        stubAdvisory(signal, cvss("3.1", "10.0"));
        stubMatch(signalId, signal,
                new ImpactedServiceMatch(portal, MatchConfidence.CONFIRMED, List.of(web), List.of()),
                new ImpactedServiceMatch(marketing, MatchConfidence.CONFIRMED, List.of(web), List.of()),
                new ImpactedServiceMatch(reporting, MatchConfidence.POSSIBLE, List.of(data), List.of()));

        List<ScoredOwner> owners = scoring.scoreChange(signalId).owners();

        assertThat(owners).extracting(owner -> owner.owner().name())
                .containsExactly("Web Platform Team", "Data Team");
        ScoredOwner web1 = owners.getFirst();
        // 80 + 50 would outrank a single genuinely critical service; the worst one is what matters.
        assertThat(web1.score()).isEqualTo(80.0);
        assertThat(web1.tier()).isEqualTo(ImpactTier.HIGH);
        assertThat(web1.impactedServiceCount()).isEqualTo(2);
        assertThat(web1.materialServiceCount()).isEqualTo(2);
        assertThat(web1.topService().name()).isEqualTo("Customer Portal");

        ScoredOwner dataTeam = owners.getLast();
        assertThat(dataTeam.score()).isEqualTo(12.5);
        assertThat(dataTeam.impactedServiceCount()).isEqualTo(1);
    }

    @Test
    void carriesMatchConfidenceThroughWithoutFoldingItIntoTheScore() {
        UUID signalId = UUID.randomUUID();
        ChangeSignal signal = signal(signalId, SignalType.VULNERABILITY, null);
        ServiceRef confirmed = service("Confirmed", BusinessCriticality.HIGH, ServiceExposure.INTERNAL);
        ServiceRef possible = service("Possible", BusinessCriticality.HIGH, ServiceExposure.INTERNAL);

        stubSignal(signal);
        stubAdvisory(signal, cvss("3.1", "10.0"));
        stubMatch(signalId, signal,
                new ImpactedServiceMatch(confirmed, MatchConfidence.CONFIRMED, List.of(), List.of()),
                new ImpactedServiceMatch(possible, MatchConfidence.POSSIBLE, List.of(), List.of()));

        List<ScoredService> services = scoring.scoreChange(signalId).services();

        // Confidence answers "does this apply", severity/exposure/criticality answer "how bad if it
        // does" — so an unreviewed match keeps its real score and is flagged, not silently discounted.
        assertThat(services).extracting(ScoredService::score).containsExactly(72.0, 72.0);
        assertThat(services).extracting(ScoredService::confidence)
                .containsExactlyInAnyOrder(MatchConfidence.CONFIRMED, MatchConfidence.POSSIBLE);
    }

    @Test
    void propagatesMatchingsRejectionOfAnUnknownOrUnmatchableSignal() {
        UUID unknown = UUID.randomUUID();
        when(matching.matchChange(unknown)).thenThrow(new ApiExceptions.NotFound("Change signal not found"));

        assertThatThrownBy(() -> scoring.scoreChange(unknown))
                .isInstanceOf(ApiExceptions.NotFound.class);
    }

    private void stubSignal(ChangeSignal signal) {
        when(signals.findById(signal.id())).thenReturn(Optional.of(signal));
    }

    private void stubAdvisory(ChangeSignal signal, Advisory.Cvss cvss) {
        Advisory advisory = advisory(signal.sourceId(), signal.externalId(), cvss);
        when(advisories.findByExternalId(signal.sourceId(), signal.externalId()))
                .thenReturn(Optional.of(advisory));
        when(advisories.findByCanonicalId(signal.externalId())).thenReturn(List.of(advisory));
    }

    private void stubMatch(UUID signalId, ChangeSignal signal, ImpactedServiceMatch... impacted) {
        when(matching.matchChange(signalId)).thenReturn(new ChangeMatchResponse(
                new ChangeRef(signal.id(), signal.signalType(), signal.sourceId(), signal.externalId(),
                        signal.title()),
                impacted.length, 0, List.of(impacted)));
    }

    private static ImpactedServiceMatch impacted(ServiceRef service) {
        return impacted(service, MatchConfidence.CONFIRMED);
    }

    private static ImpactedServiceMatch impacted(ServiceRef service, MatchConfidence confidence) {
        return new ImpactedServiceMatch(service, confidence, List.of(), List.of());
    }

    private static ServiceRef service(String name, BusinessCriticality criticality, ServiceExposure exposure) {
        return new ServiceRef(UUID.randomUUID(), name, criticality, exposure);
    }

    private static ChangeSignal signal(UUID id, SignalType type, String severity) {
        return new ChangeSignal(
                id, type, "nvd", "CVE-2099-1000", "CVE-2099-1000", "test advisory", severity, null,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
    }

    private static Advisory advisory(String sourceId, String externalId, Advisory.Cvss cvss) {
        return new Advisory(
                UUID.randomUUID(), sourceId, externalId, "CVE-2099-1000", "test advisory",
                Instant.EPOCH, Instant.EPOCH, cvss, List.of());
    }

    private static Advisory.Cvss cvss(String version, String baseScore) {
        return new Advisory.Cvss(version, null, new BigDecimal(baseScore), null);
    }
}
