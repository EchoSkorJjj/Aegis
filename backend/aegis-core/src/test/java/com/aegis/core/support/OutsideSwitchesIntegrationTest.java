package com.aegis.core.support;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every integration test runs with what reaches outside the process switched off, whatever application.yml defaults
 * to or the shell exported. A merge once dropped one of these pins silently (the fix-commit fetch, which defaults on):
 * this names each switch so that can't happen unnoticed again. Add a new outside-reaching switch here and to
 * {@link AbstractIntegrationTest} together.
 */
class OutsideSwitchesIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Environment environment;

    @Test
    void everySwitchThatReachesOutsideIsOffInIntegrationTests() {
        List<String> switches = List.of(
                "aegis.actions.github.enabled",
                "aegis.actions.jira.enabled",
                "aegis.ingestion.nvd.enabled",
                "aegis.ingestion.osv.enabled",
                "aegis.ingestion.vendor-status.enabled",
                "aegis.ingestion.news.enabled",
                "aegis.demo.seed-enabled",
                "aegis.notifications.enabled",
                "aegis.assess.fix-commits.enabled",
                "aegis.dashboard.snapshots.enabled");
        assertThat(switches).allSatisfy(name ->
                assertThat(environment.getProperty(name, Boolean.class)).as(name).isFalse());
    }
}
