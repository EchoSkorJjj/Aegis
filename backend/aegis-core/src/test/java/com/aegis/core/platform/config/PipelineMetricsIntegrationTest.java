package com.aegis.core.platform.config;

import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.memo.RegulatorMemoService;
import com.aegis.core.actions.ticket.RiskTicketService;
import com.aegis.core.actions.upgrade.UpgradePlan;
import com.aegis.core.actions.upgrade.UpgradeProposalService;
import com.aegis.core.matching.MatchingService;
import com.aegis.core.matching.VulnerableDependencies;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.scoring.ScoringService;
import com.aegis.core.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Boots real proxies and the Prometheus registry: @Timed must produce buckets, even for failed operations. */
class PipelineMetricsIntegrationTest extends AbstractIntegrationTest {
    @Autowired private MatchingService matching;
    @Autowired private VulnerableDependencies dependencies;
    @Autowired private ScoringService scoring;
    @Autowired private RiskTicketService tickets;
    @Autowired private UpgradeProposalService upgrades;
    @Autowired private RegulatorMemoService memos;
    @Autowired private CurrentOrg org;
    @Autowired private MeterRegistry registry;

    @Test
    void publicPipelineOperationsExportHistogramsIncludingFailures() {
        UUID absent = UUID.randomUUID();
        org.runAs(UUID.randomUUID(), () -> {
            assertThatThrownBy(() -> matching.matchChange(absent)).isInstanceOf(RuntimeException.class);
            dependencies.find(List.of());
            assertThatThrownBy(() -> scoring.scoreChange(absent)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> tickets.propose(absent, absent, absent)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> memos.propose(absent, absent)).isInstanceOf(RuntimeException.class);
            assertThat(upgrades.draft(mock(UpgradePlan.class), absent, absent)).isEmpty();
        });

        assertThat(registry.get("aegis.match.duration").tag("operation", "dependencies")
                .tag("exception", "none").timer().count()).isPositive();
        for (String kind : List.of("ticket", "upgrade", "memo")) {
            assertThat(registry.find("aegis.draft.duration").tag("kind", kind).timers()).isNotEmpty();
        }
        String scrape = ((PrometheusMeterRegistry) registry).scrape();
        assertThat(scrape).contains("aegis_match_duration_seconds_bucket",
                "aegis_score_duration_seconds_bucket", "aegis_draft_duration_seconds_bucket",
                "exception=\"NotFound\"");
    }
}
