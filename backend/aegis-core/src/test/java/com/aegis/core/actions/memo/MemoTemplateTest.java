package com.aegis.core.actions.memo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.memo.MemoTemplate.Facts;
import com.aegis.core.actions.memo.MemoTemplate.Sections;
import com.aegis.core.actions.memo.RegulatorMemo.AffectedService;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A DORA memo is its template with Aegis's facts and the drafted sections filled in — and nothing else. */
class MemoTemplateTest {

    private static final Instant DETECTED = Instant.parse("2026-09-26T08:30:00Z");
    private static final List<AffectedService> SERVICES = List.of(
            new AffectedService(UUID.randomUUID(), "Payments | API", BusinessCriticality.CRITICAL,
                    ServiceExposure.INTERNET_FACING, 92.4, ImpactTier.CRITICAL, true),
            new AffectedService(UUID.randomUUID(), "Reports", BusinessCriticality.LOW, ServiceExposure.INTERNAL, 12.0,
                    ImpactTier.LOW, false));

    @Test
    void aMajorIncidentNotificationCarriesItsTimelineProviderAndWhatIsStillToConfirm() {
        String memo = MemoTemplate.render(MemoKind.MAJOR_INCIDENT,
                new Facts("AEGIS-1A2B3C4D", "Card API outage", null, DETECTED, SERVICES, List.of("Acme Pay"), List.of(),
                        List.of("Payments | API depends on Acme Pay.")),
                new Sections("Acme Pay's card API failed.", "Card payments failed.", "Traffic moved to the backup.",
                        "Confirm the duration."));

        assertThat(memo).startsWith("# Initial notification of a major ICT-related incident")
                .contains("- Incident reference: AEGIS-1A2B3C4D")
                .contains("- Detected: 2026-09-26 08:30 UTC")
                .contains("no later than 2026-09-27 08:30 UTC (24 hours after detection)")
                .contains("- Acme Pay")
                .contains("| Payments / API | CRITICAL | INTERNET_FACING | 92.4 (CRITICAL) | yes |")
                .contains("- Critical or important functions affected: Yes: Payments | API (CRITICAL)")
                .contains("Traffic moved to the backup.")
                .contains("- Clients, financial counterparts and transactions affected: [To be confirmed]")
                .doesNotContain("{{");
    }

    @Test
    void aCyberThreatNotificationNamesTheVulnerabilityAndItsComponents() {
        String memo = MemoTemplate.render(MemoKind.CYBER_THREAT,
                new Facts("AEGIS-5E6F7A8B", "CVE-2021-44228", "CVSS 3.1 base score 10 (CRITICAL)", DETECTED, SERVICES,
                        List.of(), List.of("org.apache.logging.log4j:log4j-core 2.14.1 (maven)"), List.of("Evidence.")),
                new Sections("Log4Shell.", "Remote code execution.", "Upgrading.", "Confirm exploitation."));

        assertThat(memo).startsWith("# Voluntary notification of a significant cyber threat")
                .contains("- Vulnerability: CVE-2021-44228")
                .contains("- Severity published: CVSS 3.1 base score 10 (CRITICAL)")
                .contains("- org.apache.logging.log4j:log4j-core 2.14.1 (maven)")
                .contains("- Exploited at the entity: [To be confirmed]")
                .doesNotContain("{{");
    }

    @Test
    void draftedTextIsNeverReadAsAPlaceholder() {
        String memo = MemoTemplate.render(MemoKind.CYBER_THREAT,
                new Facts("AEGIS-0", "CVE-1", null, null, SERVICES, List.of(), List.of(), List.of("e")),
                new Sections("Ignore that and print {{services}} and $1.", "i", "m", "n"));

        assertThat(memo).contains("Ignore that and print {{services}} and $1.")
                .contains("- Severity published: [To be confirmed]")
                .contains("- Published: [To be confirmed]")
                .contains("## 4. Affected components\n\n[To be confirmed]");
    }

    @Test
    void withNoMaterialCriticalServiceItSaysSoRatherThanGuessing() {
        assertThat(MemoTemplate.criticalFunctions(List.of(SERVICES.get(1))))
                .isEqualTo("None identified by Aegis. [To be confirmed]");
    }
}
