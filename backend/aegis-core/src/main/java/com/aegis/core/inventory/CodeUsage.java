package com.aegis.core.inventory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.inventory.InventoryStore.ServiceImport;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ImportScan;
import com.aegis.core.inventory.domain.SbomImportSource;
import org.springframework.stereotype.Service;

/**
 * Where a service's code uses each of its dependencies (E11-3): whether the code the last import from its repository
 * read imports a package, and where — the files and lines, linked to them in the repository at the commit read.
 *
 * <p>A package is {@link Status#IMPORTED} when an import of one of its names was found, {@link Status#NOT_IMPORTED}
 * only when nothing imports a name Aegis is sure of in code the scan read completely, and {@link Status#UNKNOWN}
 * otherwise, with why: the code was never read (no linked repository, an uploaded SBOM), the dependency wasn't
 * linked by that import, the scan doesn't read its ecosystem or found no file of its language, it stopped early, the
 * code imports a Python package the name sits below without saying which of its modules ({@code import google.cloud}
 * for {@code google.cloud.storage}), or Aegis doesn't know the package's import name for sure ({@link ImportNames}).
 * An unknown package is never shown as unused.
 */
@Service
public class CodeUsage {

    /** Whether the service's code imports a package. */
    public enum Status { IMPORTED, NOT_IMPORTED, UNKNOWN }

    /** One line importing the package, and its address in the repository at the commit the scan read. */
    public record Site(String path, int line, String url) {
    }

    /**
     * What the code scan says about one package in one service: the status and, when not imported or unknown, why;
     * the names it looked for; how many imports it found, and the first {@link ImportScan#MAX_SITES} of them.
     */
    public record Usage(Status status, String reason, List<String> importedAs, int siteCount, List<Site> sites) {

        public Usage {
            importedAs = List.copyOf(importedAs);
            sites = List.copyOf(sites);
        }

        static Usage unknown(String reason, List<String> importedAs) {
            return new Usage(Status.UNKNOWN, reason, importedAs, 0, List.of());
        }
    }

    /** A package to look for: its ecosystem and name, as a component carries them. */
    public record Package(String ecosystem, String name) {
    }

    private static final Map<String, String> LANGUAGE_WORDS = Map.of(
            "npm", "JavaScript or TypeScript", "pypi", "Python", "maven", "Java or Kotlin");

    private final InventoryStore store;

    public CodeUsage(InventoryStore store) {
        this.store = store;
    }

    /** These services' code, as their last scans read it, looked up for {@code packages} in one query. */
    public Code read(Map<UUID, BusinessService> services, Collection<Package> packages) {
        Set<String> keys = new LinkedHashSet<>();
        for (Package pkg : packages) {
            ImportNames.of(pkg.ecosystem(), pkg.name()).ifPresent(names -> names.modules().forEach(module -> {
                keys.add(names.language() + ":" + module);
                // A Python package the name sits below, which may be all the code was seen to import (parentOf).
                if ("pypi".equals(names.language())) {
                    for (int dot = module.lastIndexOf('.'); dot > 0; dot = module.lastIndexOf('.', dot - 1)) {
                        keys.add("pypi:" + module.substring(0, dot));
                    }
                }
            }));
        }
        List<UUID> scanned = services.values().stream().filter(service -> service.getImportScannedAt() != null)
                .map(BusinessService::getId).toList();
        Map<UUID, List<ImportScan.ModuleImports>> imports = new HashMap<>();
        if (!scanned.isEmpty() && !keys.isEmpty()) {
            for (ServiceImport found : store.findServiceImports(scanned, keys)) {
                imports.computeIfAbsent(found.serviceId(), id -> new ArrayList<>()).add(found.imports());
            }
        }
        return new Code(services, imports);
    }

    /** The code of a set of services, read for some packages ({@link #read}). */
    public static final class Code {

        private final Map<UUID, BusinessService> services;
        private final Map<UUID, List<ImportScan.ModuleImports>> imports;

        private Code(Map<UUID, BusinessService> services, Map<UUID, List<ImportScan.ModuleImports>> imports) {
            this.services = services;
            this.imports = imports;
        }

        /**
         * Whether {@code serviceId}'s code imports the package. {@code fromRepository}: the dependency that brings it
         * into the service was linked by an import from the repository — not by hand or an uploaded SBOM, which the
         * code read may have nothing to do with.
         */
        public Usage usageOf(UUID serviceId, String ecosystem, String name, boolean fromRepository) {
            BusinessService service = services.get(serviceId);
            Optional<ImportNames.Names> names = ImportNames.of(ecosystem, name);
            List<String> importedAs = names.map(ImportNames.Names::modules).orElse(List.of());
            if (service == null || service.getImportScannedAt() == null) {
                return Usage.unknown(neverRead(service), importedAs);
            }
            if (!fromRepository) {
                return Usage.unknown("It was linked by hand or by an uploaded SBOM, not by the repository whose code "
                        + "Aegis read", importedAs);
            }
            if (names.isEmpty()) {
                return Usage.unknown("Aegis doesn't read " + ecosystem + " code yet", importedAs);
            }
            String language = names.get().language();
            if (!service.getImportScanLanguages().contains(language)) {
                return Usage.unknown("No " + LANGUAGE_WORDS.getOrDefault(language, language) + " files were read in the "
                        + "repository", importedAs);
            }
            List<ImportScan.ModuleImports> matching = imports.getOrDefault(serviceId, List.of()).stream()
                    .filter(found -> found.language().equals(language) && importedAs.stream()
                            .anyMatch(module -> matches(language, found.module(), module)))
                    .toList();
            if (!matching.isEmpty()) {
                // One line may import several of the names (from flask import request, jsonify): count it once.
                int count = (int) matching.stream().flatMap(found -> found.sites().stream()).distinct().count()
                        + matching.stream().mapToInt(found -> found.count() - found.sites().size()).sum();
                String ref = service.getImportScanRevision() != null ? service.getImportScanRevision()
                        : service.getRepositoryBranch() != null ? service.getRepositoryBranch() : "HEAD";
                List<Site> sites = matching.stream().flatMap(found -> found.sites().stream()).distinct()
                        .sorted(Comparator.comparing(ImportScan.Site::path).thenComparingInt(ImportScan.Site::line))
                        .limit(ImportScan.MAX_SITES)
                        .map(site -> new Site(site.path(), site.line(),
                                lineUrl(service.getRepositoryUrl(), ref, site.path(), site.line())))
                        .toList();
                return new Usage(Status.IMPORTED, null, importedAs, count, sites);
            }
            String looked = String.join(" or ", importedAs);
            Optional<String> parent = parentOf(language, imports.getOrDefault(serviceId, List.of()), importedAs);
            if (parent.isPresent()) {
                return Usage.unknown("The code imports " + parent.get() + ", and Aegis can't tell whether that "
                        + "includes " + looked, importedAs);
            }
            if (service.getImportScanLimit() != null) {
                return Usage.unknown("Nothing Aegis read imports " + looked + ", but the scan didn't read everything: "
                        + lowercaseFirst(service.getImportScanLimit()), importedAs);
            }
            if (!names.get().certain()) {
                return Usage.unknown("Nothing imports " + looked + ", but Aegis doesn't know for sure which name "
                        + name + " is imported as", importedAs);
            }
            return new Usage(Status.NOT_IMPORTED, "Nothing in the code Aegis read imports " + looked, importedAs, 0,
                    List.of());
        }

        /**
         * A Python package found that one of the names sits below: {@code import google.cloud}, or {@code from
         * google.cloud import …} where the scan recorded only the package, may import {@code google.cloud.storage}.
         */
        private static Optional<String> parentOf(String language, List<ImportScan.ModuleImports> found,
                List<String> names) {
            if (!"pypi".equals(language)) {
                return Optional.empty();
            }
            return found.stream().filter(module -> module.language().equals(language)).map(ImportScan.ModuleImports::module)
                    .filter(module -> names.stream().anyMatch(name -> name.startsWith(module + ".")))
                    .sorted().findFirst();
        }

        private static String neverRead(BusinessService service) {
            if (service == null || service.getRepositoryUrl() == null) {
                return "The service's code hasn't been read: link its repository and import the SBOM from it";
            }
            if (service.getSbomImportSource() == SbomImportSource.UPLOAD) {
                return "The SBOM was uploaded, so the code wasn't read: import it from the service's repository";
            }
            return "The service's code hasn't been read yet: import the SBOM from its repository";
        }
    }

    /** A module found matches a name: the same npm package; the same Python module or Java package, or one below it. */
    static boolean matches(String language, String found, String name) {
        return found.equals(name) || (!"npm".equals(language) && found.startsWith(name + "."));
    }

    /**
     * The line's address in the repository's web view: GitHub's {@code /blob/<ref>/<path>#L<line>} (and any host but
     * these), GitLab's {@code /-/blob/…}, Bitbucket's {@code /src/…#lines-<line>}. Null when the URL isn't one.
     */
    public static String lineUrl(String repositoryUrl, String ref, String path, int line) {
        if (repositoryUrl == null || !repositoryUrl.startsWith("https://")) {
            return null;
        }
        String host;
        try {
            host = URI.create(repositoryUrl).getHost();
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        if (host == null) {
            return null;
        }
        String base = repositoryUrl.endsWith("/") ? repositoryUrl.substring(0, repositoryUrl.length() - 1) : repositoryUrl;
        String encodedRef = Arrays.stream(ref.split("/")).map(CodeUsage::encode).collect(Collectors.joining("/"));
        String encodedPath = Arrays.stream(path.split("/")).map(CodeUsage::encode).collect(Collectors.joining("/"));
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (lowerHost.contains("gitlab")) {
            return base + "/-/blob/" + encodedRef + "/" + encodedPath + "#L" + line;
        }
        if (lowerHost.contains("bitbucket")) {
            return base + "/src/" + encodedRef + "/" + encodedPath + "#lines-" + line;
        }
        return base + "/blob/" + encodedRef + "/" + encodedPath + "#L" + line;
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String lowercaseFirst(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
