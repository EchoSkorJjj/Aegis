package com.aegis.core.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.matching.UpgradeTargets.Range;
import com.aegis.core.matching.UpgradeTargets.UpgradeTarget;
import com.aegis.core.platform.common.ApiExceptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpgradeTargetsTest {

    private static final String LOG4J = "org.apache.logging.log4j:log4j-core";

    @Test
    void walksPastEveryFixedRangeSoOneCveIsNeverFixedIntoAnother() {
        // Log4Shell's own fix (2.15.0) was still vulnerable; the safe upgrade clears all four CVEs.
        ComponentRef log4j = component("maven", LOG4J, "2.14.1");
        List<Range> ranges = List.of(
                range("CVE-2021-44228", log4j, maven(LOG4J, "2.0.0", "2.15.0")),
                range("CVE-2021-45046", log4j, maven(LOG4J, "2.0.0", "2.16.0")),
                range("CVE-2021-45105", log4j, maven(LOG4J, "2.0.0", "2.17.0")),
                range("CVE-2021-44832", log4j, maven(LOG4J, "2.0.0", "2.17.1")));

        UpgradeTarget target = UpgradeTargets.target("CVE-2021-44228", log4j, ranges);

        assertThat(target.fromVersion()).isEqualTo("2.14.1");
        assertThat(target.toVersion()).isEqualTo("2.17.1");
        assertThat(target.checked()).containsExactly(
                "CVE-2021-44228", "CVE-2021-44832", "CVE-2021-45046", "CVE-2021-45105");
        assertThat(target.notCleared()).isEmpty();
    }

    @Test
    void anotherCveWithoutAFixIsReportedNotBlocking() {
        ComponentRef log4j = component("maven", LOG4J, "2.14.1");
        List<Range> ranges = List.of(
                range("CVE-2021-44228", log4j, maven(LOG4J, "2.0.0", "2.15.0")),
                range("CVE-2099-0001", log4j, AffectedProduct.ofPackage("Maven", LOG4J, null, "2.0.0", null, null, null)));

        UpgradeTarget target = UpgradeTargets.target("CVE-2021-44228", log4j, ranges);

        assertThat(target.toVersion()).isEqualTo("2.15.0");
        assertThat(target.notCleared()).containsExactly("CVE-2099-0001");
    }

    @Test
    void aWeakNameCollisionElsewhereNeverPushesTheTargetButIsReported() {
        ComponentRef lodash = component("npm", "lodash", "4.17.20");
        AffectedProduct bareCpe = new AffectedProduct(
                "cpe:2.3:a:someone:lodash:*:*:*:*:*:*:*:*", "a", null, "lodash", null, "4.17.0", null, null, "4.17.30");
        List<Range> ranges = List.of(
                range("CVE-2021-23337", lodash, AffectedProduct.ofPackage("npm", "lodash", null, "0.0.0", null, null, "4.17.21")),
                range("CVE-2099-0002", lodash, bareCpe));

        UpgradeTarget target = UpgradeTargets.target("CVE-2021-23337", lodash, ranges);

        assertThat(target.toVersion()).isEqualTo("4.17.21");
        assertThat(target.notCleared()).containsExactly("CVE-2099-0002");
    }

    @Test
    void refusesWhenTheRemediatedCvePublishesNoFixedVersion() {
        ComponentRef log4j = component("maven", LOG4J, "2.14.1");
        List<Range> ranges = List.of(range("CVE-2099-0003", log4j,
                AffectedProduct.ofPackage("Maven", LOG4J, null, "2.0.0", null, "2.20.0", null)));

        assertThatThrownBy(() -> UpgradeTargets.target("CVE-2099-0003", log4j, ranges))
                .isInstanceOf(ApiExceptions.Conflict.class)
                .hasMessageContaining("publishes no fixed version");
    }

    @Test
    void refusesWhenTheInstalledVersionIsNotProvablyAffected() {
        ComponentRef log4j = component("maven", LOG4J, "2.17.1");
        List<Range> ranges = List.of(range("CVE-2021-44228", log4j, maven(LOG4J, "2.0.0", "2.15.0")));

        assertThatThrownBy(() -> UpgradeTargets.target("CVE-2021-44228", log4j, ranges))
                .isInstanceOf(ApiExceptions.Conflict.class)
                .hasMessageContaining("nothing to upgrade from");
    }

    @Test
    void refusesARemediatedRangeTheCandidateCannotBeComparedWith() {
        ComponentRef lib = component("npm", "left-pad", "1.0.0");
        List<Range> ranges = new ArrayList<>(List.of(
                range("CVE-2099-0004", lib, AffectedProduct.ofPackage("npm", "left-pad", null, "1.0.0", null, null, "2.0.0"))));
        // The first fix lands inside a second range of the same CVE whose bound is not SemVer.
        ranges.add(range("CVE-2099-0004", lib,
                AffectedProduct.ofPackage("npm", "left-pad", null, "2.0.0", null, "release-three", null)));

        assertThatThrownBy(() -> UpgradeTargets.target("CVE-2099-0004", lib, ranges))
                .isInstanceOf(ApiExceptions.Conflict.class)
                .hasMessageContaining("cannot be compared");
    }

    @Test
    void lookupKeysCoverThePurlNameAndEveryCpeProductTheIdentityRuleAccepts() {
        assertThat(UpgradeTargets.packageKeys("Org.Apache_Logging.Log4j:log4j-core"))
                .containsExactly("org-apache-logging-log4j:log4j-core");
        assertThat(UpgradeTargets.productKeys(LOG4J))
                .containsExactlyInAnyOrder("org-apache-logging-log4j-log4j-core", "log4j", "log4j-core");
        assertThat(UpgradeTargets.productKeys("requests")).containsExactly("requests");
    }

    private static ComponentRef component(String ecosystem, String name, String version) {
        return new ComponentRef(UUID.randomUUID(), ecosystem, name, version);
    }

    private static AffectedProduct maven(String name, String fromIncluding, String fixedIn) {
        return AffectedProduct.ofPackage("Maven", name, null, fromIncluding, null, null, fixedIn);
    }

    private static Range range(String vulnerability, ComponentRef component, AffectedProduct affected) {
        Range range = Range.of(vulnerability, component, affected);
        assertThat(range).as("identity of %s", affected).isNotNull();
        return range;
    }
}
