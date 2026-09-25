package com.aegis.core.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Architecture fitness tests that pin the modular-monolith package structure so it can't silently
 * erode. These analyse the compiled bytecode only — no Spring context, no Docker — so they run in
 * milliseconds as part of the normal unit-test pass.
 *
 * <p>The structure they enforce:
 *
 * <ul>
 *   <li><b>{@code ingestion}</b> is the generic pipeline <i>framework</i> (poll → capture raw events →
 *       drain) plus its source <i>connectors</i> under {@code ingestion.connectors}. The framework
 *       discovers connectors through the {@code IngestionSource} seam, so it never depends on them,
 *       and it never depends on a business domain — turning a raw payload into a domain record is the
 *       domain's job, not the framework's.</li>
 *   <li>The <b>business domains</b> — {@code advisories}, {@code incidents}, {@code news},
 *       {@code signals}, {@code inventory}, {@code matching}, {@code scoring} — are first-class
 *       top-level modules (not buried under {@code ingestion}), each owning its REST API.</li>
 *   <li><b>{@code platform}</b> is the cross-cutting base (error contract, security, tenancy, ping);
 *       features build on it, so it must not depend on any feature.</li>
 *   <li>Every module keeps its JPA entities and repositories behind a {@code Store} port in its own
 *       {@code persistence} sub-package; nothing outside {@code persistence} may reach in.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.aegis.core", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageStructureArchTest {

    /** The seven business capability modules — kept in one place so the rules read consistently. */
    private static final String[] DOMAIN_PACKAGES = {
        "com.aegis.core.advisories..",
        "com.aegis.core.incidents..",
        "com.aegis.core.news..",
        "com.aegis.core.signals..",
        "com.aegis.core.inventory..",
        "com.aegis.core.matching..",
        "com.aegis.core.scoring.."
    };

    /**
     * Persistence is an implementation detail. JPA entities, Spring Data repositories and the JPA
     * {@code Store} adapters live in a {@code persistence} sub-package and are reached only through the
     * module's {@code Store} port — never imported by a controller, service, or another module.
     */
    @ArchTest
    static final ArchRule persistence_stays_behind_the_store_port = noClasses()
            .that()
            .resideOutsideOfPackage("com.aegis.core..persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.aegis.core..persistence..")
            .because("JPA entities and repositories must stay behind each module's Store port");

    /**
     * The ingestion framework is generic infrastructure: it discovers feeds via the
     * {@code IngestionSource} bean seam, so the framework core must not depend on any connector.
     */
    @ArchTest
    static final ArchRule ingestion_framework_does_not_depend_on_connectors = noClasses()
            .that()
            .resideInAPackage("com.aegis.core.ingestion..")
            .and()
            .resideOutsideOfPackage("..ingestion.connectors..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..ingestion.connectors..")
            .because("the scheduler finds connectors through the IngestionSource seam, not by importing them");

    /**
     * The ingestion framework produces raw events; normalising one into an advisory/incident/etc. is
     * the domain's job. So neither the framework nor its connectors' shared plumbing may depend on a
     * business domain — the dependency runs the other way (a connector's normaliser writes to a
     * domain's {@code Store}).
     */
    @ArchTest
    static final ArchRule ingestion_framework_does_not_depend_on_domains = noClasses()
            .that()
            .resideInAPackage("com.aegis.core.ingestion..")
            .and()
            .resideOutsideOfPackage("..ingestion.connectors..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(DOMAIN_PACKAGES)
            .because("the framework captures raw events; turning them into domain records is the domain's job");

    /**
     * {@code platform} is the cross-cutting foundation every feature builds on (the {@code ApiError}
     * contract, security config, {@code CurrentOrg} tenancy, ping). It must not depend on any feature
     * module, or the foundation would import the things built on top of it.
     */
    @ArchTest
    static final ArchRule platform_does_not_depend_on_features = noClasses()
            .that()
            .resideInAPackage("com.aegis.core.platform..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.aegis.core.advisories..",
                    "com.aegis.core.incidents..",
                    "com.aegis.core.news..",
                    "com.aegis.core.signals..",
                    "com.aegis.core.inventory..",
                    "com.aegis.core.matching..",
                    "com.aegis.core.scoring..",
                    "com.aegis.core.ingestion..",
                    "com.aegis.core.actions..",
                    "com.aegis.core.ai..")
            .because("platform underpins the features (errors, security, tenancy); it must not depend on them");

    @ArchTest
    static final ArchRule domains_do_not_depend_on_actions = noClasses()
            .that().resideInAnyPackage(DOMAIN_PACKAGES)
            .should().dependOnClassesThat().resideInAPackage("com.aegis.core.actions..")
            .because("actions is the end of the pipeline; domains must not invoke execution");

    @ArchTest
    static final ArchRule github_is_only_reached_through_the_provider_port = noClasses()
            .that().resideOutsideOfPackage("..actions.pr.github..")
            .should().dependOnClassesThat().resideInAPackage("..actions.pr.github..")
            .because("the GitHub adapter is an optional PrProvider implementation");

    /**
     * The public web API belongs to the domains. After lifting advisories/incidents/news/signals out
     * of {@code ingestion}, no REST controller should live under the ingestion framework again.
     */
    @ArchTest
    static final ArchRule rest_controllers_do_not_live_under_ingestion = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .resideInAPackage("com.aegis.core.ingestion..")
            .because("advisory/incident/news/signal REST APIs are domains, not ingestion internals");

    /** No dependency cycles between the top-level modules (advisories, ingestion, inventory, …). */
    @ArchTest
    static final ArchRule modules_are_free_of_cycles =
            slices().matching("com.aegis.core.(*)..").should().beFreeOfCycles();
}
