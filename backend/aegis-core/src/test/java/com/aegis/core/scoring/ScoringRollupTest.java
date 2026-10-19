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
import com.aegis.core.scoring.ScoringDtos.EstateRollupResponse;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.OwnerRollup;
import com.aegis.core.scoring.ScoringDtos.ScoreFactor;
import com.aegis.core.scoring.ScoringDtos.ScoreFactorKind;
import com.aegis.core.scoring.ScoringDtos.ServiceRollup;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignal.SignalType;
import com.aegis.core.signals.ChangeSignalFilter;
import com.aegis.core.signals.ChangeSignalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScoringRollupTest {

    private static final ServiceRef PORTAL = new ServiceRef(
            UUID.randomUUID(), "Customer Portal", BusinessCriticality.HIGH, ServiceExposure.INTERNET_FACING);
    private static final ServiceRef REPORTING = new ServiceRef(
            UUID.randomUUID(), "Internal Reporting", BusinessCriticality.LOW, ServiceExposure.ISOLATED);
    private static final OwnerRef WEB = new OwnerRef(UUID.randomUUID(), UUID.randomUUID(), "Web Platform Team");
    private static final OwnerRef DATA = new OwnerRef(UUID.randomUUID(), UUID.randomUUID(), "Data Team");

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
                matching, signals, advisories, new ImpactScorer(ScoringProperties.defaults()), MockedEstate.unread());
    }

    @Test
    void aggregatesManySignalsIntoOneRankedViewPerServiceAndOwner() {
        ChangeSignal severe = vulnerability("CVE-2099-1", "10.0");
        ChangeSignal mild = vulnerability("CVE-2099-2", "4.0");
        stubPage(severe, mild);
        // The severe one hits both services; the mild one only the reporting tool.
        stubMatch(severe, impacted(PORTAL, WEB), impacted(REPORTING, DATA));
        stubMatch(mild, impacted(REPORTING, DATA));

        EstateRollupResponse rollup = scoring.rollUpEstate(ChangeSignalFilter.NONE, null);

        assertThat(rollup.signalsConsidered()).isEqualTo(2);
        assertThat(rollup.signalsImpacting()).isEqualTo(2);
        assertThat(rollup.truncated()).isFalse();

        assertThat(rollup.services()).extracting(rolled -> rolled.service().name())
                .containsExactly("Customer Portal", "Internal Reporting");
        ServiceRollup portal = rollup.services().getFirst();
        assertThat(portal.topScore()).isEqualTo(80.0);
        assertThat(portal.tier()).isEqualTo(ImpactTier.HIGH);
        assertThat(portal.signalCount()).isEqualTo(1);
        assertThat(portal.topSignal().externalId()).isEqualTo("CVE-2099-1");

        ServiceRollup reporting = rollup.services().getLast();
        // Two signals reached it; the roll-up reports its worst, and how much else is queued.
        assertThat(reporting.signalCount()).isEqualTo(2);
        assertThat(reporting.topScore()).isEqualTo(12.5);
        assertThat(reporting.topSignal().externalId()).isEqualTo("CVE-2099-1");
        assertThat(reporting.materialSignalCount()).isEqualTo(1);

        assertThat(rollup.owners()).extracting(rolled -> rolled.owner().name())
                .containsExactly("Web Platform Team", "Data Team");
        OwnerRollup data = rollup.owners().getLast();
        assertThat(data.impactedServiceCount()).isEqualTo(1);
        assertThat(data.signalCount()).isEqualTo(2);
        assertThat(data.topService().name()).isEqualTo("Internal Reporting");
    }

    @Test
    void explainsEachRolledUpTopScoreWithTheReasonsOfTheSignalThatEarnedIt() {
        ChangeSignal severe = vulnerability("CVE-2099-1", "10.0");
        ChangeSignal mild = vulnerability("CVE-2099-2", "4.0");
        // The mild signal is scored last, so a "latest wins" bug would surface its reasons instead.
        stubPage(severe, mild);
        stubMatch(severe, impacted(REPORTING, DATA));
        stubMatch(mild, impacted(REPORTING, DATA));

        EstateRollupResponse rollup = scoring.rollUpEstate(ChangeSignalFilter.NONE, null);

        ServiceRollup reporting = rollup.services().getFirst();
        assertThat(reporting.topSignal().externalId()).isEqualTo("CVE-2099-1");
        assertThat(reporting.topFactors()).extracting(ScoreFactor::input)
                .containsExactly("CVSS 3.1 base 10", "ISOLATED", "UNKNOWN", "LOW", "CONFIRMED");
        // 1.0 x 0.5 x 0.25 = 12.5; CRITICAL would make it 50.0, the most any one factor could add.
        assertThat(reporting.topFactors()).filteredOn(ScoreFactor::limiting)
                .extracting(ScoreFactor::factor)
                .containsExactly(ScoreFactorKind.CRITICALITY);
        assertThat(reporting.topExplanation())
                .contains("scores 12.5 (LOW) for Internal Reporting")
                .endsWith("; criticality (LOW) holds it down most, costing 37.5 points");

        // The owner's headline is the same score, so it carries the same reasons.
        OwnerRollup data = rollup.owners().getFirst();
        assertThat(data.topFactors()).isEqualTo(reporting.topFactors());
        assertThat(data.topExplanation()).isEqualTo(reporting.topExplanation());
    }

    @Test
    void countsASignalThatReachesNothingAsConsideredButNotImpacting() {
        ChangeSignal hits = vulnerability("CVE-2099-1", "10.0");
        ChangeSignal misses = vulnerability("CVE-2099-2", "10.0");
        stubPage(hits, misses);
        stubMatch(hits, impacted(PORTAL, WEB));
        stubMatch(misses);

        EstateRollupResponse rollup = scoring.rollUpEstate(ChangeSignalFilter.NONE, null);

        assertThat(rollup.signalsConsidered()).isEqualTo(2);
        assertThat(rollup.signalsImpacting()).isEqualTo(1);
        assertThat(rollup.services()).hasSize(1);
    }

    @Test
    void skipsSecurityNewsRatherThanFailingTheWholeRollup() {
        ChangeSignal article = new ChangeSignal(
                UUID.randomUUID(), SignalType.SECURITY_NEWS, "security-news", "post-1", "Something happened",
                null, null, null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
        ChangeSignal advisory = vulnerability("CVE-2099-1", "10.0");
        stubPage(article, advisory);
        stubMatch(advisory, impacted(PORTAL, WEB));

        EstateRollupResponse rollup = scoring.rollUpEstate(ChangeSignalFilter.NONE, null);

        // An unmatchable article is not an error here — it simply has no package or vendor identity.
        assertThat(rollup.signalsConsidered()).isEqualTo(1);
        assertThat(rollup.services()).hasSize(1);
    }

    @Test
    void skipsASignalThatCannotBeResolvedInsteadOfFailingTheWholeRollup() {
        ChangeSignal good = vulnerability("CVE-2099-1", "10.0");
        ChangeSignal broken = vulnerability("CVE-2099-2", "10.0");
        stubPage(good, broken);
        stubMatch(good, impacted(PORTAL, WEB));
        // The second signal's match blows up — e.g. its advisory vanished mid-window.
        when(matching.matchChange(broken.id())).thenThrow(new ApiExceptions.NotFound("advisory gone"));

        EstateRollupResponse rollup = scoring.rollUpEstate(ChangeSignalFilter.NONE, null);

        // The good signal still scores; the broken one is stepped over, counted, and not fatal.
        assertThat(rollup.signalsConsidered()).isEqualTo(2);
        assertThat(rollup.signalsImpacting()).isEqualTo(1);
        assertThat(rollup.signalsSkipped()).isEqualTo(1);
        assertThat(rollup.services()).hasSize(1);
    }

    @Test
    void boundsTheWorkAndSaysSoWhenTheWindowHeldMore() {
        ChangeSignal first = vulnerability("CVE-2099-1", "10.0");
        ChangeSignal second = vulnerability("CVE-2099-2", "10.0");
        // The store is asked for limit + 1 so an extra row means "there was more".
        when(signals.findPage(any(), any())).thenReturn(new PageImpl<>(List.of(first, second)));
        stubMatch(first, impacted(PORTAL, WEB));

        EstateRollupResponse rollup = scoring.rollUpEstate(ChangeSignalFilter.NONE, 1);

        assertThat(rollup.truncated()).isTrue();
        assertThat(rollup.signalsConsidered()).isEqualTo(1);
        assertThat(rollup.window().maxSignals()).isEqualTo(200);
    }

    @Test
    void clampsACallersLimitToTheConfiguredCeiling() {
        ImpactScorer bounded = new ImpactScorer(new ScoringProperties(
                null, null, null, null, null, null, null, new ScoringProperties.Rollup(5, null), null));
        ScoringService small = new ScoringService(matching, signals, advisories, bounded, MockedEstate.unread());
        when(signals.findPage(any(), any())).thenReturn(new PageImpl<>(List.of()));

        EstateRollupResponse rollup = small.rollUpEstate(ChangeSignalFilter.NONE, 10_000);

        // A caller cannot ask the server to score the entire feed by passing a huge limit.
        assertThat(rollup.window().maxSignals()).isEqualTo(5);
    }

    @Test
    void defaultsTheWindowToTheConfiguredLookbackWhenTheCallerGivesNoFrom() {
        when(signals.findPage(any(), any())).thenReturn(new PageImpl<>(List.of()));
        Instant before = Instant.now().minusSeconds(31L * 24 * 3600);

        EstateRollupResponse rollup = scoring.rollUpEstate(ChangeSignalFilter.NONE, null);

        assertThat(rollup.window().from()).isAfter(before).isBefore(Instant.now());
    }

    private void stubPage(ChangeSignal... page) {
        when(signals.findPage(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(page)));
        for (ChangeSignal signal : page) {
            when(signals.findById(signal.id())).thenReturn(Optional.of(signal));
        }
    }

    private void stubMatch(ChangeSignal signal, ImpactedServiceMatch... impacted) {
        when(matching.matchChange(signal.id())).thenReturn(new ChangeMatchResponse(
                new ChangeRef(signal.id(), signal.signalType(), signal.sourceId(), signal.externalId(),
                        signal.title()),
                impacted.length, 0, List.of(impacted)));
    }

    private ChangeSignal vulnerability(String cve, String baseScore) {
        UUID id = UUID.randomUUID();
        Advisory advisory = new Advisory(
                id, "nvd", cve, cve, "test", Instant.EPOCH, Instant.EPOCH,
                new Advisory.Cvss("3.1", null, new BigDecimal(baseScore), null), List.of());
        when(advisories.findByExternalId("nvd", cve)).thenReturn(Optional.of(advisory));
        when(advisories.findByCanonicalId(cve)).thenReturn(List.of(advisory));
        return new ChangeSignal(
                id, SignalType.VULNERABILITY, "nvd", cve, cve, "test", null, null,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
    }

    private static ImpactedServiceMatch impacted(ServiceRef service, OwnerRef owner) {
        return new ImpactedServiceMatch(service, MatchConfidence.CONFIRMED, List.of(owner), List.of());
    }
}
