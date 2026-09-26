package com.aegis.core.signals;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feed's one row per vulnerability is recorded when advisories are written (V21), no longer worked out
 * on every read, so it must follow every change the old per-query rule followed: the most recently revised
 * report represents the vulnerability, whichever feed it came from and whatever order the reports arrive in.
 */
class ChangeSignalRepresentativeIntegrationTest extends AbstractIntegrationTest {

    private static final Instant EARLIER = Instant.parse("2099-01-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2099-02-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("2099-03-01T00:00:00Z");

    @Autowired
    private AdvisoryStore advisories;

    @Autowired
    private ChangeSignalStore signals;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theMostRecentlyRevisedReportRepresentsTheVulnerabilityAndARevisionHandsItOver() {
        String cve = "CVE-2099-" + unique();
        Advisory osv = osv("GHSA-" + unique(), cve, LATER);
        Advisory nvd = nvd(cve, EARLIER);

        assertThat(represents(cve)).isEqualTo(osv.id());
        assertThat(signals.findById(osv.id())).get().extracting(ChangeSignal::title).isEqualTo(cve);
        assertThat(signals.findById(nvd.id())).isEmpty();

        nvd(cve, LATEST); // NVD revises its report
        assertThat(represents(cve)).isEqualTo(nvd.id());
        assertThat(signals.findById(osv.id())).isEmpty();
        assertThat(flagged(cve)).isEqualTo(1);
    }

    @Test
    void aReportWithNoRevisionDateRanksLast() {
        String cve = "CVE-2099-" + unique();
        Advisory nvd = nvd(cve, EARLIER);
        osv("GHSA-" + unique(), cve, null);

        assertThat(represents(cve)).isEqualTo(nvd.id());
    }

    @Test
    void aReportThatMovesToAnotherVulnerabilityLeavesBothRightlyRepresented() {
        String ghsa = "GHSA-" + unique();
        String cve = "CVE-2099-" + unique();
        Advisory nvd = nvd(cve, EARLIER);
        Advisory osv = osv(ghsa, ghsa, LATER); // known only by its GHSA at first
        assertThat(represents(ghsa)).isEqualTo(osv.id());

        osv(ghsa, cve, LATER); // OSV later aliases it to the CVE
        assertThat(signals.findVulnerabilityIds(Set.of(ghsa))).isEmpty();
        assertThat(represents(cve)).isEqualTo(osv.id());
        assertThat(flagged(cve)).isEqualTo(1);
        assertThat(signals.findById(nvd.id())).isEmpty();
    }

    @Test
    void removingTheRepresentativeHandsOverToTheNextReport() {
        String cve = "CVE-2099-" + unique();
        Advisory nvd = nvd(cve, EARLIER);
        Advisory osv = osv("GHSA-" + unique(), cve, LATER);

        jdbc.update("delete from core.advisory_affected_products where advisory_id = ?", osv.id());
        jdbc.update("delete from core.advisories where id = ?", osv.id());

        assertThat(represents(cve)).isEqualTo(nvd.id());
    }

    private UUID represents(String vulnerability) {
        return signals.findVulnerabilityIds(Set.of(vulnerability)).get(vulnerability);
    }

    private int flagged(String vulnerability) {
        return jdbc.queryForObject(
                "select count(*) from core.advisories where canonical_id = ? and representative", Integer.class,
                vulnerability);
    }

    private Advisory nvd(String cve, Instant lastModified) {
        return advisories.upsert(new Advisory(null, "nvd", cve, "NVD", EARLIER, lastModified, null, List.of()));
    }

    private Advisory osv(String ghsa, String canonical, Instant lastModified) {
        return advisories.upsert(new Advisory(null, "osv", ghsa, canonical, "OSV", EARLIER, lastModified, null,
                List.of()));
    }

    private static String unique() {
        return Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
    }
}
