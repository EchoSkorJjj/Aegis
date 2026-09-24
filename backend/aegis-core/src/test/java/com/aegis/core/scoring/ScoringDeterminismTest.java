package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.ImpactedServiceMatch;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.EstateRollupResponse;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.scoring.ScoringDtos.ScoredOwner;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignalFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static com.aegis.core.inventory.domain.BusinessCriticality.CRITICAL;
import static com.aegis.core.inventory.domain.BusinessCriticality.HIGH;
import static com.aegis.core.inventory.domain.BusinessCriticality.LOW;
import static com.aegis.core.inventory.domain.BusinessCriticality.MEDIUM;
import static com.aegis.core.inventory.domain.ServiceExposure.INTERNAL;
import static com.aegis.core.inventory.domain.ServiceExposure.INTERNET_FACING;
import static com.aegis.core.inventory.domain.ServiceExposure.ISOLATED;
import static com.aegis.core.scoring.MockedEstate.advisory;
import static com.aegis.core.scoring.MockedEstate.impacted;
import static com.aegis.core.scoring.MockedEstate.owner;
import static com.aegis.core.scoring.MockedEstate.service;
import static com.aegis.core.signals.ChangeSignal.SignalType.VULNERABILITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * Scoring determinism (CS203-74): the same estate and the same signal always produce the same scores,
 * in the same order, with the same reasons — whatever order the database or the matcher happens to
 * hand things over in, and however many times it is asked. A score someone acted on must be
 * reproducible later, and a work queue that reshuffles between two page loads is not a queue.
 */
class ScoringDeterminismTest {

    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");

    private final ImpactScorer scorer = new ImpactScorer(ScoringProperties.defaults());
    private final MockedEstate estate = new MockedEstate();
    private final ScoringService scoring = estate.scoring(scorer);

    // ---- the arithmetic ---------------------------------------------------------------------------

    @Test
    void everyScoreIsTheExactDecimalProductRoundedHalfUp() {
        // Every published CVSS score (0.0-10.0 in tenths) and every severity word, on every service
        // profile and confidence, under the shipped weights and under a confidence discount — against
        // the product worked out in exact decimal arithmetic. Floating point must never move a score
        // across a rounding boundary: 33.75 is 33.8, not 33.7, and never a different answer on a rerun.
        ImpactScorer discounting = new ImpactScorer(new ScoringProperties(
                null, null, Map.of(MatchConfidence.POSSIBLE, 0.7), null, null, null, null, null));
        for (ImpactScorer model : List.of(scorer, discounting)) {
            List<SeverityInput> severities = new ArrayList<>();
            for (int tenths = 0; tenths <= 100; tenths++) {
                severities.add(model.severityFromCvss("3.1", BigDecimal.valueOf(tenths, 1)));
            }
            for (String word : List.of("critical", "high", "major", "medium", "minor", "low", "none")) {
                severities.add(model.severityFromLabel(word));
            }
            severities.add(model.severityFromLabel(null));

            for (SeverityInput severity : severities) {
                for (ServiceExposure exposure : ServiceExposure.values()) {
                    for (BusinessCriticality criticality : BusinessCriticality.values()) {
                        for (MatchConfidence confidence : MatchConfidence.values()) {
                            ScoreBreakdown breakdown = model.breakdown(VULNERABILITY, severity.factor(), exposure, criticality, confidence);
                            BigDecimal exact = decimal(severity)
                                    .multiply(BigDecimal.valueOf(breakdown.exposureFactor()))
                                    .multiply(BigDecimal.valueOf(breakdown.criticalityFactor()))
                                    .multiply(BigDecimal.valueOf(breakdown.confidenceFactor()))
                                    .multiply(BigDecimal.valueOf(100))
                                    .setScale(1, RoundingMode.HALF_UP);
                            assertThat(BigDecimal.valueOf(model.score(breakdown)))
                                    .as("%s on %s/%s/%s", describe(severity), exposure, criticality, confidence)
                                    .isEqualByComparingTo(exact);
                        }
                    }
                }
            }
        }
    }

    // ---- one change, scored ------------------------------------------------------------------------

    @Test
    void scoringTheSameChangeTwiceGivesTheSameAnswer() {
        ChangeSignal signal = estate.vulnerability("CVE-2099-0001", "9.8", NOW);
        estate.reaches(signal, anEstateWithTies());

        assertThat(scoring.scoreChange(signal.id())).isEqualTo(scoring.scoreChange(signal.id()));
    }

    @Test
    void theRankingDoesNotDependOnTheOrderMatchingReturnsServicesIn() {
        ChangeSignal signal = estate.vulnerability("CVE-2099-0002", "7.5", NOW);
        List<ImpactedServiceMatch> services = anEstateWithTies();
        estate.reaches(signal, services);
        ChangeImpactResponse baseline = scoring.scoreChange(signal.id());

        Random random = new Random(74);
        for (int shuffle = 0; shuffle < 50; shuffle++) {
            List<ImpactedServiceMatch> reordered = new ArrayList<>(services);
            Collections.shuffle(reordered, random);
            estate.reaches(signal, reordered);

            assertThat(scoring.scoreChange(signal.id())).as("shuffle %d", shuffle).isEqualTo(baseline);
        }
    }

    @Test
    void equalScoresRankByNameIgnoringCaseThenById() {
        // Four services with the same profile — the same score — and names that differ only in case,
        // two of them identical. Ties must break on something stable, not on arrival order.
        ServiceRef beta = service(new UUID(0, 4), "beta", HIGH, INTERNAL);
        ServiceRef alphaUpper = service(new UUID(0, 3), "Alpha", HIGH, INTERNAL);
        ServiceRef alphaSecond = service(new UUID(0, 2), "alpha", HIGH, INTERNAL);
        ServiceRef alphaFirst = service(new UUID(0, 1), "alpha", HIGH, INTERNAL);
        OwnerRef zed = owner(new UUID(1, 2), "zed");
        OwnerRef ann = owner(new UUID(1, 1), "Ann");
        ChangeSignal signal = estate.vulnerability("CVE-2099-0003", "9.8", NOW);
        estate.reaches(signal, impacted(beta, zed), impacted(alphaUpper, zed), impacted(alphaSecond, ann),
                impacted(alphaFirst, ann));

        ChangeImpactResponse response = scoring.scoreChange(signal.id());

        assertThat(response.services()).extracting(ScoredService::score).containsOnly(70.6);
        assertThat(response.services()).extracting(scored -> scored.service().id())
                .containsExactly(alphaFirst.id(), alphaSecond.id(), alphaUpper.id(), beta.id());
        assertThat(response.owners()).extracting(ScoredOwner::score).containsOnly(70.6);
        assertThat(response.owners()).extracting(ranked -> ranked.owner().name()).containsExactly("Ann", "zed");
        // An owner's representative service on a tie is their first in that same stable order.
        assertThat(response.owners().getFirst().topService().id()).isEqualTo(alphaFirst.id());
        assertThat(response.owners().getLast().topService().id()).isEqualTo(alphaUpper.id());
    }

    @Test
    void theReportedSeverityDoesNotDependOnWhichFeedTheDatabaseReturnsFirst() {
        // NVD and a second feed publish the same base score for the same CVE under different CVSS
        // generations. Which row Postgres returns first must not decide what the response — and the
        // explanation built from it — says.
        ChangeSignal signal = estate.vulnerability("CVE-2099-0004", "9.8", NOW);
        estate.reaches(signal, impacted(service("Payments", CRITICAL, INTERNET_FACING)));
        Advisory nvd = advisory("nvd", "CVE-2099-0004", "CVE-2099-0004", "3.1", "9.8");
        Advisory osv = advisory("osv", "GHSA-0004", "CVE-2099-0004", "3.0", "9.8");

        estate.feeds(signal, nvd, osv);
        ChangeImpactResponse nvdFirst = scoring.scoreChange(signal.id());
        estate.feeds(signal, osv, nvd);
        ChangeImpactResponse osvFirst = scoring.scoreChange(signal.id());

        assertThat(osvFirst).isEqualTo(nvdFirst);
        // On an equal score the newer CVSS generation is the one reported.
        assertThat(nvdFirst.severity().cvssVersion()).isEqualTo("3.1");
    }

    @Test
    void aHigherScoreFromAnyFeedStillWinsOverANewerCvssGeneration() {
        // The tie-break is only for ties: under-scoring because one feed lagged is the worse failure.
        ChangeSignal signal = estate.vulnerability("CVE-2099-0005", "7.5", NOW);
        estate.reaches(signal, impacted(service("Payments", CRITICAL, INTERNET_FACING)));
        estate.feeds(signal,
                advisory("nvd", "CVE-2099-0005", "CVE-2099-0005", "3.1", "7.5"),
                advisory("osv", "GHSA-0005", "CVE-2099-0005", "2.0", "9.3"));

        SeverityInput severity = scoring.scoreChange(signal.id()).severity();

        assertThat(severity.cvssBaseScore()).isEqualByComparingTo("9.3");
        assertThat(severity.cvssVersion()).isEqualTo("2.0");
    }

    // ---- the roll-up -------------------------------------------------------------------------------

    @Test
    void theRollupAsksForTheNewestSignalsInAStableOrder() {
        estate.window();

        scoring.rollUpEstate(ChangeSignalFilter.NONE, 10);

        // Newest first is what makes a truncated roll-up cover the newest signals, as promised; the id
        // breaks ties between signals that occurred at the same instant, so the page is the same page
        // on every call.
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(estate.signals).findPage(any(), page.capture());
        assertThat(page.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.asc("id")));
        assertThat(page.getValue().getPageSize()).isEqualTo(11); // one extra, to detect truncation
    }

    @Test
    void aRolledUpTieIsWonByTheNewestSignal() {
        ServiceRef payments = service("Payments", CRITICAL, INTERNET_FACING);
        ChangeSignal newer = estate.vulnerability("CVE-2099-0006", "9.8", NOW);
        ChangeSignal older = estate.vulnerability("CVE-2099-0007", "9.8", NOW.minusSeconds(60));
        estate.reaches(newer, impacted(payments));
        estate.reaches(older, impacted(payments));
        estate.window(newer, older);

        EstateRollupResponse rollup = scoring.rollUpEstate(ChangeSignalFilter.NONE, null);

        assertThat(rollup.services().getFirst().topSignal().externalId()).isEqualTo("CVE-2099-0006");
    }

    @Test
    void rollingUpTheSameWindowTwiceGivesTheSameAnswer() {
        List<ImpactedServiceMatch> services = anEstateWithTies();
        ChangeSignal first = estate.vulnerability("CVE-2099-0008", "9.8", NOW);
        ChangeSignal second = estate.vulnerability("CVE-2099-0009", "5.3", NOW.minusSeconds(1));
        ChangeSignal outage = estate.outage("acme:1", "major", NOW.minusSeconds(2));
        estate.reaches(first, services);
        estate.reaches(second, services.subList(0, 3));
        estate.reaches(outage, services.subList(2, 6));
        estate.window(first, second, outage);
        // A fixed window: with no `from`, the roll-up reaches back from "now", which is not repeatable.
        ChangeSignalFilter window = new ChangeSignalFilter(null, null, null, null, null,
                NOW.minusSeconds(3600), NOW);

        assertThat(scoring.rollUpEstate(window, null)).isEqualTo(scoring.rollUpEstate(window, null));
    }

    /** Services with every kind of tie: equal profiles, names differing in case, shared owners. */
    private static List<ImpactedServiceMatch> anEstateWithTies() {
        OwnerRef web = owner("Web");
        OwnerRef data = owner("data");
        OwnerRef platform = owner("Platform");
        return List.of(
                impacted(service("Payments", CRITICAL, INTERNET_FACING), web, platform),
                impacted(service("payments", CRITICAL, INTERNET_FACING), web),
                impacted(service("Orders", HIGH, INTERNAL), platform),
                impacted(service("Portal", HIGH, INTERNET_FACING), web),
                impacted(service("Reporting", LOW, ISOLATED), MatchConfidence.POSSIBLE, data),
                impacted(service("Marketing", MEDIUM, INTERNET_FACING)),
                impacted(service("Batch", LOW, ISOLATED), data));
    }

    private static BigDecimal decimal(SeverityInput severity) {
        // A CVSS factor is the published score over ten; a word's factor is the configured decimal.
        return severity.cvssBaseScore() != null
                ? severity.cvssBaseScore().movePointLeft(1)
                : BigDecimal.valueOf(severity.factor());
    }

    private static String describe(SeverityInput severity) {
        return severity.cvssBaseScore() != null ? "CVSS " + severity.cvssBaseScore() : "label " + severity.label();
    }
}
