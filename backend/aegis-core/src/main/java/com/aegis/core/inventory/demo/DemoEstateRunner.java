package com.aegis.core.inventory.demo;

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

    DemoEstateRunner(DemoEstateSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Demo estate seeded ({} business services)", seeder.seed());
    }
}
