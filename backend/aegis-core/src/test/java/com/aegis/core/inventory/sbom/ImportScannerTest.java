package com.aegis.core.inventory.sbom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.aegis.core.inventory.domain.ImportScan;
import com.aegis.core.inventory.domain.ImportScan.ModuleImports;
import com.aegis.core.inventory.domain.ImportScan.Site;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a checkout for what its code imports (E11-3): every way JavaScript, TypeScript, Python, Java and Kotlin
 * import a module, and everything the scan leaves alone — relative imports, the standard libraries, comments, what
 * isn't the repository's own code, symbolic links and files that look like secrets — and where it stops.
 */
class ImportScannerTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path checkout;

    @Test
    void javascriptAndTypescriptImportsAreFoundByPackageWithTheirLines() throws Exception {
        write("web/src/app.tsx", """
                import React, { useState } from "react";
                import type { Metadata } from 'next';
                import {
                  Button,
                  Card,
                } from "@acme/ui/components";
                import "./styles.css";
                import "tailwindcss/tailwind.css";
                export { default as Link } from "next/link";
                const lodash = require("lodash/fp");
                const lazy = await import("@hono/node-server");
                import local from "../lib/local";
                import alias from "@/components/alias";
                import fs from "node:fs";
                import path from "path";
                // import commented from "commented-out";
                /* import blocked from "blocked"; */
                """);

        Map<String, List<Site>> found = sites(ImportScanner.scan(checkout, Set.of(), REVISION), "npm");

        assertThat(found).containsOnlyKeys("react", "next", "@acme/ui", "tailwindcss", "lodash", "@hono/node-server");
        assertThat(found.get("react")).containsExactly(new Site("web/src/app.tsx", 1));
        assertThat(found.get("next")).containsExactly(new Site("web/src/app.tsx", 2), new Site("web/src/app.tsx", 9));
        // A multi-line import is found on the line naming the module.
        assertThat(found.get("@acme/ui")).containsExactly(new Site("web/src/app.tsx", 6));
        assertThat(found.get("lodash")).containsExactly(new Site("web/src/app.tsx", 10));
        assertThat(found.get("@hono/node-server")).containsExactly(new Site("web/src/app.tsx", 11));
    }

    @Test
    void anImportInsideABlockCommentIsNotReadAndLinesAfterItKeepTheirNumbers() throws Exception {
        write("web/src/legacy.ts", """
                /*
                import hidden from "in-a-block-comment";
                const old = require("old-require");
                 */
                /**
                 * Usage: import { thing } from "in-a-doc-comment";
                 */
                const pattern = /\\/*$/; import afterRegex from "after-a-regex";
                const url = "https://cdn.example.test/*"; import afterUrl from "after-a-url";
                const tick = `/* ${"*/"} `; import afterTemplate from "after-a-template";
                import real from "real"; /* trailing
                import alsoHidden from "also-hidden"; */ import afterClose from "after-the-close";
                """);
        write("svc/src/main/java/com/acme/Legacy.java", """
                package com.acme;

                /*
                import org.hidden.Thing;
                 */
                // import org.commented.Thing;
                import org.visible.Thing; // import org.trailing.Thing;
                class Legacy {
                    String url = "http://example.test/*";
                }
                import org.after.Thing;
                """);

        ImportScan scan = ImportScanner.scan(checkout, Set.of(), REVISION);

        Map<String, List<Site>> javascript = sites(scan, "npm");
        assertThat(javascript).containsOnlyKeys("after-a-regex", "after-a-url", "after-a-template", "real",
                "after-the-close");
        assertThat(javascript.get("after-a-regex")).containsExactly(new Site("web/src/legacy.ts", 8));
        assertThat(javascript.get("after-the-close")).containsExactly(new Site("web/src/legacy.ts", 12));
        Map<String, List<Site>> jvm = sites(scan, "maven");
        assertThat(jvm).containsOnlyKeys("org.visible", "org.after");
        assertThat(jvm.get("org.after")).containsExactly(new Site("svc/src/main/java/com/acme/Legacy.java", 11));
    }

    @Test
    void pythonImportsAreFoundByModuleAndTheStandardLibraryAndRelativeImportsAreLeftOut() throws Exception {
        write("api/main.py", """
                import os, sys
                import yaml
                import google.protobuf.json_format as json_format, requests  # two at once
                from PIL import Image
                from jose.jwt import decode
                from . import sibling
                from .models import Thing
                    import attr
                # import commented
                """);

        Map<String, List<Site>> found = sites(ImportScanner.scan(checkout, Set.of(), REVISION), "pypi");

        assertThat(found).containsOnlyKeys("yaml", "google.protobuf.json_format", "requests", "PIL", "jose.jwt.decode",
                "attr");
        assertThat(found.get("requests")).containsExactly(new Site("api/main.py", 3));
        assertThat(found.get("attr")).containsExactly(new Site("api/main.py", 8));
    }

    @Test
    void aPythonFromImportRecordsEachSubmoduleItMayImport() throws Exception {
        write("api/storage.py", """
                from google.cloud import storage
                from google.cloud import (
                    bigquery,
                    pubsub_v1 as pubsub,  # a comment inside
                )
                from flask import Flask, request
                from sqlalchemy.orm import *
                from botocore \\
                    import session
                from myapp.billing import charge
                \"""Usage:

                import inside_a_docstring
                from inside.a.docstring import thing
                \"""
                LABEL = "# import not_a_comment"
                import after_the_docstring
                """);
        write("myapp/__init__.py", "");
        write("myapp/billing/__init__.py", "def charge():\n    pass\n");

        Map<String, List<Site>> found = sites(ImportScanner.scan(checkout, Set.of(), REVISION), "pypi");

        // google-cloud-storage is imported as google.cloud.storage: found, not just google.cloud.
        assertThat(found).containsOnlyKeys("google.cloud.storage", "google.cloud.bigquery", "google.cloud.pubsub_v1",
                "flask", "flask.request", "sqlalchemy.orm", "botocore.session", "myapp.billing", "after_the_docstring");
        assertThat(found.get("google.cloud.storage")).containsExactly(new Site("api/storage.py", 1));
        // A parenthesised or continued import is found on its first line.
        assertThat(found.get("google.cloud.pubsub_v1")).containsExactly(new Site("api/storage.py", 2));
        assertThat(found.get("botocore.session")).containsExactly(new Site("api/storage.py", 8));
        assertThat(found.get("after_the_docstring")).containsExactly(new Site("api/storage.py", 17));
    }

    @Test
    void theRepositorysOwnPythonModulesAreItsOutermostPackagesAndLooseFiles() {
        Path root = Path.of("/checkout");
        Set<String> own = ImportScanner.ownPythonModules(root, List.of(root.resolve("src/myapp/__init__.py"),
                root.resolve("src/myapp/api/__init__.py"), root.resolve("src/myapp/api/routes.py"),
                root.resolve("scripts/seed.py"), root.resolve("integrations/google/client.py"),
                root.resolve("web/app.ts")));

        assertThat(own).containsExactlyInAnyOrder("myapp", "seed", "client");
    }

    @Test
    void javaAndKotlinImportsAreFoundByPackage() throws Exception {
        write("svc/src/main/java/com/acme/App.java", """
                package com.acme;

                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.google.common.collect.ImmutableList;
                import org.apache.commons.io.*;
                import static org.junit.Assert.assertEquals;
                import static org.mockito.Mockito.*;
                import java.util.List;
                import javax.servlet.http.HttpServletRequest;
                import com.acme.Outer.Inner;
                """);
        write("svc/src/main/kotlin/App.kt", """
                import kotlinx.coroutines.launch
                import okhttp3.OkHttpClient as Client
                import kotlin.collections.List
                """);

        Map<String, List<Site>> found = sites(ImportScanner.scan(checkout, Set.of(), REVISION), "maven");

        assertThat(found).containsOnlyKeys("com.fasterxml.jackson.databind", "com.google.common.collect",
                "org.apache.commons.io", "org.junit", "org.mockito", "javax.servlet.http", "com.acme", "kotlinx.coroutines",
                "okhttp3");
        assertThat(found.get("okhttp3")).containsExactly(new Site("svc/src/main/kotlin/App.kt", 2));
    }

    @Test
    void whatIsNotTheRepositorysOwnCodeLinksAndSecretLookingFilesAreLeftUnread() throws Exception {
        write("src/index.js", "import own from 'own-package';\n");
        write("node_modules/dep/index.js", "import theirs from 'theirs';\n");
        write("dist/bundle.js", "import built from 'built';\n");
        write("src/vendor.min.js", "import minified from 'minified';\n");
        write(".venv/lib/site.py", "import venv_only\n");
        write("src/secrets.ts", "import leaked from 'leaked';\n");
        write("src/.env.local.js", "import env from 'env-file';\n");
        // A link the repository records, checked out as a plain file holding its target.
        write("src/linked.ts", "import viaLink from 'via-link';\n");
        Path real = write("elsewhere/real.ts", "import realTarget from 'real-target';\n");
        Files.createSymbolicLink(checkout.resolve("src/symlink.ts"), real);

        ImportScan scan = ImportScanner.scan(checkout, Set.of("src/linked.ts"), REVISION);

        assertThat(scan.modules()).extracting(ModuleImports::module).containsExactlyInAnyOrder("own-package", "real-target");
        assertThat(scan.filesRead()).isEqualTo(2);
        assertThat(scan.languages()).containsExactly("npm");
        assertThat(scan.limit()).isNull();
        assertThat(scan.revision()).isEqualTo(REVISION);
    }

    @Test
    void itStopsAtItsLimitsAndSaysSo() throws Exception {
        write("src/huge.ts", "import big from 'big';\n" + "x".repeat((int) ImportScanner.MAX_FILE_BYTES));
        write("src/ok.ts", "import ok from 'ok';\n");

        ImportScan scan = ImportScanner.scan(checkout, Set.of(), null);

        assertThat(scan.modules()).extracting(ModuleImports::module).containsExactly("ok");
        assertThat(scan.limit()).isEqualTo("1 source file was larger than 1024 KB and not read");
    }

    @Test
    void aModuleImportedManyTimesKeepsItsCountAndTheFirstSites() throws Exception {
        for (int i = 0; i < ImportScan.MAX_SITES + 5; i++) {
            write("src/file" + String.format("%02d", i) + ".ts", "import x from 'many';\n");
        }

        ModuleImports many = ImportScanner.scan(checkout, Set.of(), null).modules().getFirst();

        assertThat(many.count()).isEqualTo(ImportScan.MAX_SITES + 5);
        assertThat(many.sites()).hasSize(ImportScan.MAX_SITES).first().isEqualTo(new Site("src/file00.ts", 1));
    }

    @Test
    void aSpecifierNamesItsPackage() {
        assertThat(ImportScanner.npmPackage("@scope/pkg/deep/path")).isEqualTo("@scope/pkg");
        assertThat(ImportScanner.npmPackage("pkg/sub")).isEqualTo("pkg");
        assertThat(ImportScanner.npmPackage("@scope")).isNull();
        assertThat(ImportScanner.npmPackage("#internal")).isNull();
        assertThat(ImportScanner.npmPackage("~/lib")).isNull();
        assertThat(ImportScanner.npmPackage("https://cdn.example.test/x.js")).isNull();
        assertThat(ImportScanner.npmPackage("crypto")).isNull();
    }

    private Path write(String path, String content) throws Exception {
        Path file = checkout.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static Map<String, List<Site>> sites(ImportScan scan, String language) {
        return scan.modules().stream().filter(module -> module.language().equals(language))
                .collect(Collectors.toMap(ModuleImports::module, ModuleImports::sites));
    }
}
