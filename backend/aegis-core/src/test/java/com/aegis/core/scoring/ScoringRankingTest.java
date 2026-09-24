package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.ImpactedServiceMatch;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.EstateRollupResponse;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.OwnerRollup;
import com.aegis.core.scoring.ScoringDtos.ScoredOwner;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.scoring.ScoringDtos.ServiceRollup;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignalFilter;
import org.junit.jupiter.api.Test;

import static com.aegis.core.inventory.domain.BusinessCriticality.CRITICAL;
import static com.aegis.core.inventory.domain.ServiceExposure.INTERNET_FACING;
import static com.aegis.core.inventory.domain.ServiceExposure.ISOLATED;
import static com.aegis.core.scoring.MockedEstate.impacted;
import static com.aegis.core.scoring.MockedEstate.owner;
import static com.aegis.core.scoring.MockedEstate.service;
import static com.aegis.core.signals.ChangeSignal.SignalType.VULNERABILITY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ranking (CS203-74): the properties that make a score usable as a work queue. Each is checked over
 * the whole input grid, or over randomised estates with a fixed seed so a failure reproduces exactly:
 * a worse input never ranks lower, a tier never contradicts its score, every list is in score order,
 * an owner stands where their worst service stands, and the estate roll-up agrees with scoring each
 * signal on its own.
 */
class ScoringRankingTest {

    private static final long SEED = 74L;
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
    private static final List<String> CVSS = cvssGrid();
    private static final List<String> IMPACT_WORDS = List.of("critical", "major", "minor", "none");

    private final ImpactScorer scorer = new ImpactScorer(ScoringProperties.defaults());

    // ---- monotonicity: a worse input never ranks lower ---------------------------------------------

    @Test
    void aMoreSevereVulnerabilityNeverScoresLower() {
        for (ServiceExposure exposure : ServiceExposure.values()) {
            for (BusinessCriticality criticality : BusinessCriticality.values()) {
                double previous = -1;
                for (String cvss : CVSS) {
                    double score = cvssScore(cvss, exposure, criticality, MatchConfidence.CONFIRMED);
                    assertThat(score).as("CVSS %s on %s/%s", cvss, exposure, criticality)
                            .isGreaterThanOrEqualTo(previous);
                    previous = score;
                }
            }
        }
    }

    @Test
    void aMoreExposedServiceNeverScoresLowerForTheSameVulnerability() {
        for (String cvss : CVSS) {
            for (BusinessCriticality criticality : BusinessCriticality.values()) {
                double previous = -1;
                for (ServiceExposure exposure : ServiceExposure.values()) { // least to most exposed
                    double score = cvssScore(cvss, exposure, criticality, MatchConfidence.CONFIRMED);
                    assertThat(score).as("CVSS %s on %s/%s", cvss, exposure, criticality)
                            .isGreaterThanOrEqualTo(previous);
                    previous = score;
                }
            }
        }
    }

    @Test
    void aMoreCriticalServiceNeverScoresLowerForTheSameChange() {
        for (String cvss : CVSS) {
            for (ServiceExposure exposure : ServiceExposure.values()) {
                double previous = -1;
                for (BusinessCriticality criticality : BusinessCriticality.values()) { // LOW to CRITICAL
                    double score = cvssScore(cvss, exposure, criticality, MatchConfidence.CONFIRMED);
                    assertThat(score).as("CVSS %s on %s/%s", cvss, exposure, criticality)
                            .isGreaterThanOrEqualTo(previous);
                    previous = score;
                }
            }
        }
    }

    @Test
    void aConfirmedMatchNeverRanksBelowAPossibleOneEvenUnderADiscount() {
        ImpactScorer discounting = new ImpactScorer(new ScoringProperties(
                null, null, Map.of(MatchConfidence.POSSIBLE, 0.6), null, null, null, null, null));
        for (String cvss : CVSS) {
            for (ServiceExposure exposure : ServiceExposure.values()) {
                for (BusinessCriticality criticality : BusinessCriticality.values()) {
                    SeverityInput severity = discounting.severityFromCvss("3.1", new BigDecimal(cvss));
                    double confirmed = discounting.score(discounting.breakdown(VULNERABILITY,
                            severity.factor(), exposure, criticality, MatchConfidence.CONFIRMED));
                    double possible = discounting.score(discounting.breakdown(VULNERABILITY,
                            severity.factor(), exposure, criticality, MatchConfidence.POSSIBLE));
                    assertThat(confirmed).as("CVSS %s on %s/%s", cvss, exposure, criticality)
                            .isGreaterThanOrEqualTo(possible);
                }
            }
        }
    }

    @Test
    void aWorseOutageWordNeverScoresLower() {
        List<String> leastToWorst = List.of("none", "minor", "major", "critical");
        for (ServiceExposure exposure : ServiceExposure.values()) {
            for (BusinessCriticality criticality : BusinessCriticality.values()) {
                double previous = -1;
                for (String word : leastToWorst) {
                    SeverityInput severity = scorer.severityFromLabel(word);
                    double score = scorer.score(scorer.breakdown(ChangeSignal.SignalType.VENDOR_OUTAGE,
                            severity.factor(), exposure, criticality, MatchConfidence.CONFIRMED));
                    assertThat(score).as("'%s' on %s/%s", word, exposure, criticality).isGreaterThanOrEqualTo(previous);
                    previous = score;
                }
            }
        }
    }

    @Test
    void theTierNeverContradictsTheScore() {
        // Walking every displayable score upwards, the tier only ever climbs — a higher score can never
        // sit in a lower band — and the noise line is exactly the NEGLIGIBLE line, so "immaterial" and
        // "NEGLIGIBLE" never disagree about a row.
        ImpactTier previous = ImpactTier.NEGLIGIBLE;
        for (int tenths = 0; tenths <= 1000; tenths++) {
            double score = tenths / 10.0;
            ImpactTier tier = scorer.tier(score);
            assertThat(tier).as("score %.1f", score).isGreaterThanOrEqualTo(previous);
            assertThat(scorer.material(score)).as("score %.1f", score).isEqualTo(tier != ImpactTier.NEGLIGIBLE);
            previous = tier;
        }
    }

    // ---- one change: a work queue ------------------------------------------------------------------

    @Test
    void everyScoredChangeIsAWorkQueue() {
        Random random = new Random(SEED);
        for (int estateNo = 0; estateNo < 200; estateNo++) {
            MockedEstate estate = new MockedEstate();
            ScoringService scoring = estate.scoring(scorer);
            List<OwnerRef> people = people(random, 4);
            ChangeSignal signal = random.nextBoolean()
                    ? estate.vulnerability("CVE-2099-" + estateNo, pick(random, CVSS), NOW)
                    : estate.outage("acme:" + estateNo, pick(random, IMPACT_WORDS), NOW);
            estate.reaches(signal, randomServices(random, people, 1 + random.nextInt(12)));

            ChangeImpactResponse response = scoring.scoreChange(signal.id());
            String context = "estate " + estateNo + " (seed " + SEED + ")";

            assertThat(response.services()).extracting(ScoredService::score).as(context)
                    .isSortedAccordingTo(Comparator.reverseOrder());
            assertThat(response.owners()).extracting(ScoredOwner::score).as(context)
                    .isSortedAccordingTo(Comparator.reverseOrder());
            for (ScoredService scored : response.services()) {
                assertThat(scored.tier()).as(context).isEqualTo(scorer.tier(scored.score()));
                assertThat(scored.material()).as(context).isEqualTo(scorer.material(scored.score()));
            }
            for (ScoredOwner ranked : response.owners()) {
                // An owner stands exactly where their worst service stands — never a sum.
                List<ScoredService> theirs = response.services().stream()
                        .filter(scored -> scored.owners().contains(ranked.owner())).toList();
                assertThat(ranked.score()).as(context).isEqualTo(theirs.getFirst().score());
                assertThat(ranked.topService()).as(context).isEqualTo(theirs.getFirst().service());
                assertThat(ranked.tier()).as(context).isEqualTo(scorer.tier(ranked.score()));
                assertThat(ranked.impactedServiceCount()).as(context).isEqualTo(theirs.size());
                assertThat(ranked.materialServiceCount()).as(context)
                        .isEqualTo((int) theirs.stream().filter(ScoredService::material).count());
            }
        }
    }

    // ---- the estate roll-up: agrees with scoring each signal on its own ----------------------------

    @Test
    void theRollupAgreesWithScoringEachSignalOnItsOwn() {
        Random random = new Random(SEED);
        for (int estateNo = 0; estateNo < 60; estateNo++) {
            MockedEstate estate = new MockedEstate();
            ScoringService scoring = estate.scoring(scorer);
            List<OwnerRef> people = people(random, 4);
            List<ImpactedServiceMatch> services = randomServices(random, people, 8);

            List<ChangeSignal> window = new ArrayList<>(); // newest first, as the store returns it
            for (int signalNo = 0; signalNo < 1 + random.nextInt(10); signalNo++) {
                Instant occurred = NOW.minusSeconds(signalNo * 60L);
                ChangeSignal signal = random.nextInt(3) > 0
                        ? estate.vulnerability("CVE-2099-%d-%d".formatted(estateNo, signalNo), pick(random, CVSS), occurred)
                        : estate.outage("acme:%d-%d".formatted(estateNo, signalNo), pick(random, IMPACT_WORDS), occurred);
                estate.reaches(signal, services.stream().filter(ignored -> random.nextInt(3) == 0).toList());
                window.add(signal);
            }
            estate.window(window.toArray(ChangeSignal[]::new));

            EstateRollupResponse rollup = scoring.rollUpEstate(ChangeSignalFilter.NONE, null);
            String context = "estate " + estateNo + " (seed " + SEED + ")";

            // Score every signal on its own, the way GET /api/scoring/change-signals/{id} would.
            Map<UUID, List<Hit>> hitsByService = new LinkedHashMap<>();
            int impacting = 0;
            for (ChangeSignal signal : window) {
                ChangeImpactResponse alone = scoring.scoreChange(signal.id());
                if (!alone.services().isEmpty()) {
                    impacting++;
                }
                for (ScoredService scored : alone.services()) {
                    hitsByService.computeIfAbsent(scored.service().id(), ignored -> new ArrayList<>())
                            .add(new Hit(signal, scored));
                }
            }

            assertThat(rollup.signalsConsidered()).as(context).isEqualTo(window.size());
            assertThat(rollup.signalsImpacting()).as(context).isEqualTo(impacting);
            assertThat(rollup.services()).extracting(ServiceRollup::topScore).as(context)
                    .isSortedAccordingTo(Comparator.reverseOrder());
            assertThat(rollup.owners()).extracting(OwnerRollup::topScore).as(context)
                    .isSortedAccordingTo(Comparator.reverseOrder());
            assertThat(rollup.services()).extracting(rolled -> rolled.service().id()).as(context)
                    .containsExactlyInAnyOrderElementsOf(hitsByService.keySet());

            Map<UUID, Double> topScoreByService = new HashMap<>();
            for (ServiceRollup rolled : rollup.services()) {
                List<Hit> hits = hitsByService.get(rolled.service().id());
                double worst = hits.stream().mapToDouble(hit -> hit.scored().score()).max().orElseThrow();
                // The first hit at the worst score is the newest one, since the window is newest-first.
                Hit newestWorst = hits.stream().filter(hit -> hit.scored().score() == worst).findFirst().orElseThrow();

                assertThat(rolled.topScore()).as(context).isEqualTo(worst);
                assertThat(rolled.tier()).as(context).isEqualTo(scorer.tier(worst));
                assertThat(rolled.signalCount()).as(context).isEqualTo(hits.size());
                assertThat(rolled.materialSignalCount()).as(context)
                        .isEqualTo((int) hits.stream().filter(hit -> hit.scored().material()).count());
                assertThat(rolled.topSignal().id()).as(context).isEqualTo(newestWorst.signal().id());
                assertThat(rolled.topExplanation()).as(context).isEqualTo(newestWorst.scored().explanation());
                topScoreByService.put(rolled.service().id(), rolled.topScore());
            }
            for (OwnerRollup rolled : rollup.owners()) {
                Set<UUID> theirs = new LinkedHashSet<>();
                for (ServiceRollup service : rollup.services()) {
                    List<Hit> hits = hitsByService.get(service.service().id());
                    if (hits.getFirst().scored().owners().contains(rolled.owner())) {
                        theirs.add(service.service().id());
                    }
                }
                double worst = theirs.stream().mapToDouble(topScoreByService::get).max().orElseThrow();
                assertThat(rolled.topScore()).as(context).isEqualTo(worst);
                assertThat(rolled.impactedServiceCount()).as(context).isEqualTo(theirs.size());
            }
        }
    }

    @Test
    void oneSevereHitOutranksAnyNumberOfMildOnes() {
        // Worst, not sum: twenty 3.0s on a critical, internet-facing service still rank below a single
        // 9.8 on the least valuable service — as long as that 9.8 outscores each of them individually.
        MockedEstate estate = new MockedEstate();
        ScoringService scoring = estate.scoring(scorer);
        OwnerRef many = owner("Many small things");
        OwnerRef one = owner("One big thing");
        ServiceRef busy = service("Busy", CRITICAL, INTERNET_FACING);
        ServiceRef quiet = service("Quiet", CRITICAL, ISOLATED);
        List<ChangeSignal> window = new ArrayList<>();
        for (int n = 0; n < 20; n++) {
            ChangeSignal mild = estate.vulnerability("CVE-2099-MILD-" + n, "3.0", NOW.minusSeconds(n + 1));
            estate.reaches(mild, impacted(busy, many));
            window.add(mild);
        }
        ChangeSignal severe = estate.vulnerability("CVE-2099-SEVERE", "9.8", NOW.minusSeconds(100));
        estate.reaches(severe, impacted(quiet, one));
        window.add(severe);
        estate.window(window.toArray(ChangeSignal[]::new));

        EstateRollupResponse rollup = scoring.rollUpEstate(ChangeSignalFilter.NONE, null);

        // 0.3 x 1.0 x 1.0 = 30.0 each on Busy; 0.98 x 0.5 x 1.0 = 49.0 once on Quiet.
        assertThat(rollup.services()).extracting(rolled -> rolled.service().name()).containsExactly("Quiet", "Busy");
        assertThat(rollup.services().getLast().signalCount()).isEqualTo(20);
        assertThat(rollup.owners()).extracting(rolled -> rolled.owner().name())
                .containsExactly("One big thing", "Many small things");
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private double cvssScore(String cvss, ServiceExposure exposure, BusinessCriticality criticality, MatchConfidence confidence) {
        SeverityInput severity = scorer.severityFromCvss("3.1", new BigDecimal(cvss));
        return scorer.score(scorer.breakdown(VULNERABILITY, severity.factor(), exposure, criticality, confidence));
    }

    private static List<ImpactedServiceMatch> randomServices(Random random, List<OwnerRef> people, int count) {
        List<ImpactedServiceMatch> services = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            ServiceRef service = service("svc-" + n,
                    pick(random, List.of(BusinessCriticality.values())), pick(random, List.of(ServiceExposure.values())));
            List<OwnerRef> owners = people.stream().filter(ignored -> random.nextInt(3) == 0).toList();
            MatchConfidence confidence = random.nextInt(4) == 0 ? MatchConfidence.POSSIBLE : MatchConfidence.CONFIRMED;
            services.add(impacted(service, confidence, owners.toArray(OwnerRef[]::new)));
        }
        return services;
    }

    private static List<OwnerRef> people(Random random, int count) {
        List<OwnerRef> people = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            people.add(owner(new UUID(random.nextLong(), random.nextLong()), "person-" + n));
        }
        return people;
    }

    private static <T> T pick(Random random, List<T> options) {
        return options.get(random.nextInt(options.size()));
    }

    private static List<String> cvssGrid() {
        List<String> grid = new ArrayList<>();
        for (int tenths = 0; tenths <= 100; tenths++) {
            grid.add(BigDecimal.valueOf(tenths, 1).toPlainString());
        }
        return grid;
    }

    private record Hit(ChangeSignal signal, ScoredService scored) {
    }
}
