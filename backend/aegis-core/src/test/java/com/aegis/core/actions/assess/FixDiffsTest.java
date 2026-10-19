package com.aegis.core.actions.assess;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a fix commit changed, summarised for the AI (E11-5): each changed source file of the package and the functions
 * or classes the host's diff names around its changes — or, where it names none, a few changed lines. Tests, docs and
 * release notes are left out: they say nothing about where the package is vulnerable.
 */
class FixDiffsTest {

    private static final String JACKSON = """
            diff --git a/release-notes/VERSION-2.x b/release-notes/VERSION-2.x
            index 1..2 100644
            --- a/release-notes/VERSION-2.x
            +++ b/release-notes/VERSION-2.x
            @@ -14,6 +14,10 @@ Project: jackson-databind
            +#6129: Path deserialization scheme allowlist
            diff --git a/src/main/java/com/fasterxml/jackson/databind/ext/NioPathDeserializer.java b/src/main/java/com/fasterxml/jackson/databind/ext/NioPathDeserializer.java
            index 3..4 100644
            --- a/src/main/java/com/fasterxml/jackson/databind/ext/NioPathDeserializer.java
            +++ b/src/main/java/com/fasterxml/jackson/databind/ext/NioPathDeserializer.java
            @@ -8,8 +8,11 @@
             import java.nio.file.Path;
            +import java.util.Set;
            @@ -25,6 +28,14 @@ public class NioPathDeserializer extends StdScalarDeserializer<Path>
            +    private static final Set<String> ALLOWED_SCHEMES = Set.of("file", "jar");
            @@ -66,12 +107,22 @@ public Path deserialize(JsonParser p, DeserializationContext ctxt) throws IOExce
            -        return Path.of(uri);
            +        return resolve(uri);
            @@ -86,4 +137,37 @@ public Path deserialize(JsonParser p, DeserializationContext ctxt) throws IOExce
            +    }
            diff --git a/src/test/java/com/fasterxml/jackson/databind/ext/TestJava7Types.java b/src/test/java/com/fasterxml/jackson/databind/ext/TestJava7Types.java
            index 5..6 100644
            @@ -31,6 +37,134 @@ public void testPathRoundtrip() throws Exception
            +    public void testCustomScheme() {}
            """;

    private static final String LODASH = """
            diff --git a/lodash.js b/lodash.js
            index 1..2 100644
            --- a/lodash.js
            +++ b/lodash.js
            @@ -165,6 +166,18 @@
            +  /** Used to detect invalid characters in the `variable` option of `_.template`. */
            +  var reForbiddenIdentifierChars = /[()=,{}\\[\\]\\/\\s]/;
            @@ -14866,6 +14879,12 @@
                 var variable = hasOwnProperty.call(options, 'variable') && options.variable;
            +    if (!variable) {
            +      source = 'with (obj) {\\n' + source + '\\n}\\n';
            +    }
            +    else if (reForbiddenIdentifierChars.test(variable)) {
            +      throw new Error(INVALID_TEMPL_VAR_ERROR_TEXT);
            +    }
            diff --git a/test/test.js b/test/test.js
            @@ -22296,6 +22296,14 @@
            +    QUnit.test('should not let a variable be code', function(assert) {});
            """;

    @Test
    void eachChangedSourceFileWithTheFunctionsTheDiffNamesAroundItsChanges() {
        assertThat(FixDiffs.summary(JACKSON, 1200)).isEqualTo(
                "src/main/java/com/fasterxml/jackson/databind/ext/NioPathDeserializer.java: changed in public class"
                        + " NioPathDeserializer extends StdScalarDeserializer<Path>; public Path deserialize(JsonParser p,"
                        + " DeserializationContext ctxt) throws IOExce");
    }

    @Test
    void whereTheDiffNamesNoFunctionAFewChangedLinesSayWhatChanged() {
        assertThat(FixDiffs.summary(LODASH, 1200)).isEqualTo(
                "lodash.js: lines changed: /** Used to detect invalid characters in the `variable` option of `_.template`. */"
                        + " | var reForbiddenIdentifierChars = /[()=,{}\\[\\]\\/\\s]/; | if (!variable) {");
    }

    @Test
    void theSummaryStopsAtItsLimitBetweenFiles() {
        String two = JACKSON + LODASH;
        String whole = FixDiffs.summary(two, 1200);
        assertThat(whole.lines()).hasSize(2);
        assertThat(FixDiffs.summary(two, 300)).isEqualTo(FixDiffs.summary(JACKSON, 1200));
        assertThat(FixDiffs.summary("not a diff", 1200)).isEmpty();
    }
}
