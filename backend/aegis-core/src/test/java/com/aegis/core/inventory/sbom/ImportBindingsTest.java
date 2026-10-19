package com.aegis.core.inventory.sbom;

import java.util.List;

import com.aegis.core.inventory.sbom.ImportBindings.Binding;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names an import binds, and the lines of the file using them (E11-5): what the AI is shown instead of the import
 * itself, so it can see what the code does with the package. Read statically, per language; a wildcard or a
 * re-export binds nothing, and a TypeScript type-only import nothing that runs.
 */
class ImportBindingsTest {

    @Test
    void javaScriptDefaultNamedAliasedAndNamespaceImports() {
        assertThat(names("npm", "import Default, { merge, template as tpl, type Options } from 'lodash';", 1))
                .containsExactly("Default", "merge", "tpl");
        assertThat(names("npm", "import * as _ from 'lodash';", 1)).containsExactly("_");
        assertThat(names("npm", "import { default as lodash } from \"lodash\"", 1)).containsExactly("lodash");
    }

    @Test
    void aMultiLineImportIsReadFromItsFirstLineToTheLineNamingTheModule() {
        String text = """
                import { join } from 'node:path';
                import {
                  merge,
                  template as tpl,
                } from 'lodash';
                """;
        Binding binding = ImportBindings.at("npm", text, 5);
        assertThat(binding.names()).containsExactly("merge", "tpl");
        assertThat(binding.firstLine()).isEqualTo(2);
        assertThat(binding.lastLine()).isEqualTo(5);
    }

    @Test
    void requireAndDynamicImportsBindWhatTheyAreAssignedTo() {
        assertThat(names("npm", "const _ = require('lodash');", 1)).containsExactly("_");
        assertThat(names("npm", "const { pick, template: tpl, merge = noop } = require('lodash');", 1))
                .containsExactly("pick", "tpl", "merge");
        assertThat(names("npm", "const tpl = require('lodash').template;", 1)).containsExactly("tpl");
        assertThat(names("npm", "  const lib = await import('lodash');", 1)).containsExactly("lib");
    }

    @Test
    void aReExportASideEffectImportAndATypeOnlyImportBindNothingThatRuns() {
        assertThat(names("npm", "export { merge } from 'lodash';", 1)).isEmpty();
        assertThat(names("npm", "import 'lodash/fp';", 1)).isEmpty();
        Binding types = ImportBindings.at("npm", "import type { Metadata } from 'next';", 1);
        assertThat(types.names()).isEmpty();
        assertThat(types.typeOnly()).isTrue();
        assertThat(ImportBindings.at("npm", "import { type Metadata, type Viewport } from 'next';", 1).typeOnly())
                .isTrue();
    }

    @Test
    void pythonImportsAndFromImports() {
        assertThat(names("pypi", "import yaml", 1)).containsExactly("yaml");
        assertThat(names("pypi", "import google.cloud.storage", 1)).containsExactly("google.cloud.storage");
        assertThat(names("pypi", "import xml.etree.ElementTree as ET, json", 1)).containsExactly("ET", "json");
        assertThat(names("pypi", "from yaml import load, safe_load as sl", 1)).containsExactly("load", "sl");
        assertThat(names("pypi", "from yaml import *", 1)).isEmpty();
        Binding parenthesised = ImportBindings.at("pypi", "from jinja2 import (\n    Template,\n    Environment as Env,\n)\n", 1);
        assertThat(parenthesised.names()).containsExactly("Template", "Env");
        assertThat(parenthesised.lastLine()).isEqualTo(4);
    }

    @Test
    void javaAndKotlinImports() {
        assertThat(names("maven", "import com.fasterxml.jackson.databind.ObjectMapper;", 1)).containsExactly("ObjectMapper");
        assertThat(names("maven", "import static org.apache.commons.text.StringSubstitutor.replace;", 1))
                .containsExactly("replace");
        assertThat(names("maven", "import org.apache.commons.text.*;", 1)).isEmpty();
        assertThat(names("maven", "import org.yaml.snakeyaml.Yaml as SnakeYaml", 1)).containsExactly("SnakeYaml");
    }

    @Test
    void theLinesUsingANameAreThoseOutsideImportsAndCommentsThatNameItThemselves() {
        String text = """
                import { template } from 'lodash';
                import { render } from './view';

                // template is the vulnerable one
                export function page(req) {
                  const view = template(req.body.view);
                  return req.template + render(req);
                }
                /* template(here) is a comment */
                const again = template;
                """;
        Binding binding = ImportBindings.at("npm", text, 1);
        assertThat(ImportBindings.usages("npm", text, binding)).containsExactly(6, 10);
    }

    @Test
    void aMemberOfANamespaceAndADottedPythonModuleAreUsesToo() {
        String js = "const _ = require('lodash');\nmodule.exports = (s) => _.template(s);\n";
        assertThat(ImportBindings.usages("npm", js, ImportBindings.at("npm", js, 1))).containsExactly(2);
        String py = "import google.cloud.storage\n# google.cloud.storage.Client()\nclient = google.cloud.storage.Client()\n";
        assertThat(ImportBindings.usages("pypi", py, ImportBindings.at("pypi", py, 1))).containsExactly(3);
    }

    @Test
    void aVariableOrFieldHoldingWhatWasImportedIsFollowedToWhereItIsUsed() {
        // What the code does with the package is often on the variable, not the class: one hop is followed.
        String java = """
                import com.fasterxml.jackson.databind.ObjectMapper;
                class Client {
                  private final ObjectMapper objectMapper = new ObjectMapper();
                  String send(Object response) throws Exception {
                    return this.objectMapper.writeValueAsString(response);
                  }
                  private final ObjectMapper reader; // injected
                  Object read(String json) throws Exception { return reader.readValue(json, Object.class); }
                  String other() { return objectMapperName; }
                }
                """;
        assertThat(ImportBindings.usages("maven", java, ImportBindings.at("maven", java, 1))).containsExactly(3, 5, 7, 8);

        String js = "const _ = require('lodash');\nconst tpl = _.template;\nexport const render = (v) => tpl(v);\n";
        assertThat(ImportBindings.usages("npm", js, ImportBindings.at("npm", js, 1))).containsExactly(2, 3);
        String py = "import yaml\nloader = yaml.FullLoader\ndata = yaml.load(text, Loader=loader)\n";
        assertThat(ImportBindings.usages("pypi", py, ImportBindings.at("pypi", py, 1))).containsExactly(2, 3);
    }

    @Test
    void whetherALineIsAnImportInItsLanguage() {
        assertThat(ImportBindings.isImport("npm", "import x from 'y';")).isTrue();
        assertThat(ImportBindings.isImport("npm", "const x = require('y');")).isTrue();
        assertThat(ImportBindings.isImport("npm", "const x = y;")).isFalse();
        assertThat(ImportBindings.isImport("pypi", "from a import b")).isTrue();
        assertThat(ImportBindings.isImport("maven", "import java.nio.file.Path;")).isTrue();
        assertThat(ImportBindings.isImport("maven", "private Path file;")).isFalse();
    }

    private static List<String> names(String language, String text, int line) {
        return List.copyOf(ImportBindings.at(language, text, line).names());
    }
}
