package com.aegis.core.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.inventory.CodeUsage.Status;
import com.aegis.core.inventory.CodeUsage.Usage;
import com.aegis.core.inventory.InventoryStore.ServiceImport;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ImportScan;
import com.aegis.core.inventory.domain.ImportScan.ModuleImports;
import com.aegis.core.inventory.domain.ImportScan.Site;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.domain.SbomImportSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whether a service's code imports a package (E11-3): found when an import of one of its names is, not imported only
 * when nothing imports a name Aegis is sure of in code it read completely, and unknown — never unused — otherwise.
 */
class CodeUsageTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    private final InventoryStore store = mock(InventoryStore.class);
    private final CodeUsage usage = new CodeUsage(store);

    @Test
    void aPackageItsCodeImportsIsImportedWithEachLineLinkedAtTheCommitRead() {
        BusinessService service = scanned("https://github.com/acme/web", List.of("npm", "pypi"), null);
        imports(service, new ModuleImports("npm", "@hono/node-server", 3, List.of(new Site("src/app.ts", 7),
                new Site("src/my file.ts", 2))), new ModuleImports("pypi", "yaml.loader", 1,
                List.of(new Site("api/main.py", 4))));

        CodeUsage.Code code = read(service);

        Usage hono = code.usageOf(service.getId(), "npm", "@hono/node-server", true);
        assertThat(hono.status()).isEqualTo(Status.IMPORTED);
        assertThat(hono.siteCount()).isEqualTo(3);
        assertThat(hono.sites()).extracting(CodeUsage.Site::url).containsExactly(
                "https://github.com/acme/web/blob/" + REVISION + "/src/app.ts#L7",
                "https://github.com/acme/web/blob/" + REVISION + "/src/my%20file.ts#L2");
        // PyYAML is imported as yaml: a module below it counts.
        Usage pyyaml = code.usageOf(service.getId(), "pypi", "PyYAML", true);
        assertThat(pyyaml.status()).isEqualTo(Status.IMPORTED);
        assertThat(pyyaml.importedAs()).containsExactly("yaml");
    }

    @Test
    void notImportedOnlyWhenTheNameIsCertainAndTheCodeWasReadCompletely() {
        BusinessService service = scanned("https://github.com/acme/web", List.of("npm", "pypi", "maven"), null);
        CodeUsage.Code code = read(service);

        assertThat(code.usageOf(service.getId(), "npm", "lodash", true))
                .extracting(Usage::status, Usage::reason)
                .containsExactly(Status.NOT_IMPORTED, "Nothing in the code Aegis read imports lodash");
        assertThat(code.usageOf(service.getId(), "pypi", "beautifulsoup4", true).status()).isEqualTo(Status.NOT_IMPORTED);
        assertThat(code.usageOf(service.getId(), "maven", "com.google.guava:guava", true).status())
                .isEqualTo(Status.NOT_IMPORTED);
        // Guessed names: their absence proves nothing.
        Usage unmapped = code.usageOf(service.getId(), "pypi", "some-dist", true);
        assertThat(unmapped.status()).isEqualTo(Status.UNKNOWN);
        assertThat(unmapped.reason()).contains("doesn't know for sure which name some-dist is imported as");
        assertThat(code.usageOf(service.getId(), "maven", "org.springframework:spring-web", true).status())
                .isEqualTo(Status.UNKNOWN);
        assertThat(code.usageOf(service.getId(), "maven", "org.apache.logging.log4j:log4j-core", true).status())
                .isEqualTo(Status.UNKNOWN);

        BusinessService limited = scanned("https://github.com/acme/big", List.of("npm"),
                "It stopped after reading 20000 of 30000 source files");
        assertThat(read(limited).usageOf(limited.getId(), "npm", "lodash", true).reason())
                .isEqualTo("Nothing Aegis read imports lodash, but the scan didn't read everything: it stopped after "
                        + "reading 20000 of 30000 source files");
    }

    @Test
    void aPythonSubmoduleImportedFromItsPackageIsImportedAndOnlyItsPackageSeenIsUnknown() {
        // from google.cloud import storage
        BusinessService storage = scanned("https://github.com/acme/storage", List.of("pypi"), null);
        imports(storage, new ModuleImports("pypi", "google.cloud.storage", 1, List.of(new Site("api/main.py", 1))));
        assertThat(read(storage).usageOf(storage.getId(), "pypi", "google-cloud-storage", true).status())
                .isEqualTo(Status.IMPORTED);

        // import google.cloud: which of its modules the code uses isn't known, so neither is google-cloud-storage.
        BusinessService parent = scanned("https://github.com/acme/parent", List.of("pypi"), null);
        imports(parent, new ModuleImports("pypi", "google.cloud", 1, List.of(new Site("api/main.py", 1))));
        assertThat(read(parent).usageOf(parent.getId(), "pypi", "google-cloud-storage", true))
                .extracting(Usage::status, Usage::reason)
                .containsExactly(Status.UNKNOWN, "The code imports google.cloud, and Aegis can't tell whether that "
                        + "includes google.cloud.storage");

        // from google.cloud import bigquery: another module, so storage isn't imported.
        BusinessService sibling = scanned("https://github.com/acme/sibling", List.of("pypi"), null);
        imports(sibling, new ModuleImports("pypi", "google.cloud.bigquery", 1, List.of(new Site("api/main.py", 1))));
        assertThat(read(sibling).usageOf(sibling.getId(), "pypi", "google-cloud-storage", true).status())
                .isEqualTo(Status.NOT_IMPORTED);

        // The packages above the name are looked up too, so a scan that recorded only them is found.
        verify(store, atLeastOnce()).findServiceImports(anyCollection(),
                argThat(keys -> keys.containsAll(List.of("pypi:google.cloud.storage", "pypi:google.cloud", "pypi:google"))));
    }

    @Test
    void aLineImportingSeveralOfAPackagesModulesCountsOnce() {
        // from flask import request, jsonify, on two lines; and 30 more lines below the first twenty sites kept.
        BusinessService service = scanned("https://github.com/acme/flask", List.of("pypi"), null);
        imports(service,
                new ModuleImports("pypi", "flask.request", 2, List.of(new Site("app.py", 1), new Site("views.py", 3))),
                new ModuleImports("pypi", "flask.jsonify", 1, List.of(new Site("app.py", 1))));

        Usage flask = read(service).usageOf(service.getId(), "pypi", "flask", true);

        assertThat(flask.siteCount()).isEqualTo(2);
        assertThat(flask.sites()).extracting(CodeUsage.Site::path).containsExactly("app.py", "views.py");
    }

    @Test
    void whatTheScanCannotSpeakForIsUnknownWithWhy() {
        BusinessService scanned = scanned("https://github.com/acme/web", List.of("npm"), null);
        CodeUsage.Code code = read(scanned);
        assertThat(code.usageOf(scanned.getId(), "npm", "lodash", false).reason())
                .startsWith("It was linked by hand or by an uploaded SBOM");
        assertThat(code.usageOf(scanned.getId(), "cargo", "serde", true).reason())
                .isEqualTo("Aegis doesn't read cargo code yet");
        assertThat(code.usageOf(scanned.getId(), "pypi", "requests", true).reason())
                .isEqualTo("No Python files were read in the repository");

        BusinessService noRepository = new BusinessService(UUID.randomUUID(), "No repo", BusinessCriticality.LOW);
        assertThat(read(noRepository).usageOf(noRepository.getId(), "npm", "lodash", true))
                .extracting(Usage::status, Usage::reason)
                .containsExactly(Status.UNKNOWN, "The service's code hasn't been read: link its repository and import "
                        + "the SBOM from it");
        BusinessService uploaded = new BusinessService(UUID.randomUUID(), "Uploaded", BusinessCriticality.LOW);
        uploaded.linkRepository(RepositoryUrl.parse("https://github.com/acme/up", List.of("github.com")), null);
        uploaded.recordSbomImport(SbomImportSource.UPLOAD, Instant.now());
        assertThat(read(uploaded).usageOf(uploaded.getId(), "npm", "lodash", true).reason())
                .startsWith("The SBOM was uploaded, so the code wasn't read");
    }

    @Test
    void theRepositorysOwnWebViewIsLinked() {
        assertThat(CodeUsage.lineUrl("https://gitlab.com/group/sub/repo", "main", "a b/c.py", 3))
                .isEqualTo("https://gitlab.com/group/sub/repo/-/blob/main/a%20b/c.py#L3");
        assertThat(CodeUsage.lineUrl("https://bitbucket.org/acme/repo", REVISION, "src/x.ts", 9))
                .isEqualTo("https://bitbucket.org/acme/repo/src/" + REVISION + "/src/x.ts#lines-9");
        assertThat(CodeUsage.lineUrl("https://github.com/acme/repo", "feat/client-frontend", "x.ts", 1))
                .isEqualTo("https://github.com/acme/repo/blob/feat/client-frontend/x.ts#L1");
        assertThat(CodeUsage.lineUrl(null, "main", "x.ts", 1)).isNull();
    }

    @Test
    void namesFollowEachEcosystemsConventionOrAreKnown() {
        assertThat(ImportNames.of("npm", "@hono/node-server")).get()
                .isEqualTo(new ImportNames.Names("npm", List.of("@hono/node-server"), true));
        assertThat(ImportNames.of("PyPI", "Python_JOSE")).get()
                .isEqualTo(new ImportNames.Names("pypi", List.of("jose"), true));
        assertThat(ImportNames.of("pypi", "python-foo")).get()
                .isEqualTo(new ImportNames.Names("pypi", List.of("python_foo", "foo"), false));
        assertThat(ImportNames.of("maven", "com.google.guava:guava")).get()
                .isEqualTo(new ImportNames.Names("maven", List.of("com.google.common", "com.google.thirdparty"), true));
        assertThat(ImportNames.of("maven", "commons-io:commons-io-extra")).get()
                .isEqualTo(new ImportNames.Names("maven", List.of("org.apache.commons.io"), false));
        assertThat(ImportNames.of("maven", "org.acme:thing")).get()
                .isEqualTo(new ImportNames.Names("maven", List.of("org.acme"), false));
        assertThat(ImportNames.of("golang", "github.com/x/y")).isEmpty();
        assertThat(CodeUsage.matches("pypi", "yaml.loader", "yaml")).isTrue();
        assertThat(CodeUsage.matches("pypi", "yamlish", "yaml")).isFalse();
        assertThat(CodeUsage.matches("npm", "hono.x", "hono")).isFalse();
    }

    private BusinessService scanned(String repository, List<String> languages, String limit) {
        BusinessService service = new BusinessService(UUID.randomUUID(), "Scanned " + repository,
                BusinessCriticality.HIGH);
        service.linkRepository(RepositoryUrl.parse(repository, List.of("github.com")), null);
        service.recordSbomImport(SbomImportSource.PUBLIC, Instant.now());
        service.recordImportScan(new ImportScan(REVISION, languages, 10, limit, List.of()), Instant.now());
        return service;
    }

    private void imports(BusinessService service, ModuleImports... modules) {
        when(store.findServiceImports(anyCollection(), anyCollection())).thenReturn(List.of(modules).stream()
                .map(module -> new ServiceImport(service.getId(), module)).toList());
    }

    private CodeUsage.Code read(BusinessService service) {
        return usage.read(Map.of(service.getId(), service), List.of(new CodeUsage.Package("npm", "x"),
                new CodeUsage.Package("pypi", "google-cloud-storage")));
    }
}
