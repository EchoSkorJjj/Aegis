package com.aegis.core.inventory.demo;

import com.aegis.core.platform.tenant.CurrentOrg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Seeds the demo estate at startup when {@code aegis.demo.seed-enabled=true} (dev/demo only). */
@Component
@ConditionalOnProperty(name = "aegis.demo.seed-enabled", havingValue = "true")
class DemoEstateRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoEstateRunner.class);

    private final DemoEstateSeeder seeder;
    private final CurrentOrg currentOrg;

    DemoEstateRunner(DemoEstateSeeder seeder, CurrentOrg currentOrg) {
        this.seeder = seeder;
        this.currentOrg = currentOrg;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Startup has no request/token, so establish the demo org explicitly for the seed's writes.
        int[] seeded = {0};
        currentOrg.runAs(DemoEstateSeeder.DEMO_ORG_ID, () -> seeded[0] = seeder.seed());
        log.info("Demo estate seeded ({} business services)", seeded[0]);
    }
}
