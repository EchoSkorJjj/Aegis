package com.aegis.core.inventory.domain;

import java.util.List;

/**
 * What reading a service's code found it importing (E11-3), at an import from its repository: every module imported,
 * in each language, with where. The scan reads source text only — it never runs the repository's code — and stores
 * module names, paths and line numbers, never the code.
 *
 * @param revision  the commit the scan read, for links to its lines; null when it couldn't be told
 * @param languages the ecosystems it read source files of ({@code npm}, {@code pypi}, {@code maven}): one without a
 *                  file read says nothing about what is imported
 * @param filesRead how many source files it read
 * @param limit     why it stopped before reading everything, in words a person can act on; null when it read all
 * @param modules   each module imported, per language
 */
public record ImportScan(String revision, List<String> languages, int filesRead, String limit,
                         List<ModuleImports> modules) {

    /** The sites kept for one module; its count says how many there are in all. */
    public static final int MAX_SITES = 20;

    public ImportScan {
        languages = List.copyOf(languages);
        modules = List.copyOf(modules);
    }

    /**
     * One module imported in one language: an npm package ({@code @hono/node-server}), a Python module
     * ({@code google.protobuf}) or a Java package ({@code com.fasterxml.jackson.databind}); how many times, and the first
     * {@link #MAX_SITES} places.
     */
    public record ModuleImports(String language, String module, int count, List<Site> sites) {

        public ModuleImports {
            sites = List.copyOf(sites);
        }
    }

    /** One line importing a module: the repository-relative path and the 1-based line. */
    public record Site(String path, int line) {

        /** {@code src/app.ts:12}, as stored. */
        public String encoded() {
            return path + ":" + line;
        }

        /** The inverse of {@link #encoded}: the line is after the last colon. */
        public static Site decode(String encoded) {
            int colon = encoded.lastIndexOf(':');
            return new Site(encoded.substring(0, colon), Integer.parseInt(encoded.substring(colon + 1)));
        }
    }
}
