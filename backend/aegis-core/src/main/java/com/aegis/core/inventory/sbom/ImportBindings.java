package com.aegis.core.inventory.sbom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names an import binds and the lines of the file using them (E11-5), read statically like {@link ImportScanner}'s
 * imports: so the AI is shown what the code does with a package, not the import itself. JavaScript and TypeScript
 * (default, named with {@code as}, namespace, {@code require} and destructuring, an awaited {@code import()}); Python
 * ({@code import a.b}, {@code as}, {@code from … import}); Java and Kotlin (a class, a static member, Kotlin's
 * {@code as}). A wildcard and a re-export bind nothing, and a TypeScript type-only import nothing that runs.
 */
final class ImportBindings {

    /** The longest import statement read, in lines. */
    private static final int MAX_STATEMENT = 30;
    /** The most variables holding what was imported that are followed. */
    private static final int MAX_HELD = 5;
    private static final Set<String> NOT_NAMES = Set.of("new", "return", "this", "self", "await", "const", "let",
            "var", "val", "final", "static", "private", "public", "protected", "readonly");

    private static final Pattern JS_START = Pattern.compile("^\\s*(?:import\\b|export\\b|(?:const|let|var)\\b)");
    private static final Pattern JS_FROM = Pattern.compile("^\\s*import\\s+(type\\s+)?([\\s\\S]*?)\\s*\\bfrom\\s*['\"]");
    private static final Pattern JS_ASSIGNED = Pattern.compile(
            "^\\s*(?:const|let|var)\\s+([\\s\\S]+?)\\s*=\\s*(?:await\\s+)?(?:require|import)\\s*\\(");
    private static final Pattern JS_IMPORT_LINE = Pattern.compile(
            "^\\s*(?:import\\b|export\\b[^;]*\\bfrom\\b)|\\brequire\\s*\\(|\\bimport\\s*\\(");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

    private static final Pattern PY_FROM = Pattern.compile("^\\s*from\\s+[\\w.]+\\s+import\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern PY_IMPORT = Pattern.compile("^\\s*import\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern PY_NAME = Pattern.compile("[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*");
    private static final Pattern PY_IMPORT_LINE = Pattern.compile("^\\s*(?:from\\s+[\\w.]+\\s+import\\b|import\\s+[\\w.])");

    private static final Pattern JVM_IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)"
            + "(\\.\\*)?(?:\\s+as\\s+([A-Za-z_]\\w*))?\\s*;?\\s*$");
    private static final Pattern JVM_IMPORT_LINE = Pattern.compile("^\\s*import\\s+[\\w.]");

    private ImportBindings() {
    }

    /**
     * What one import binds: its {@code names}, the lines its statement spans, and — TypeScript — whether it imports
     * only types, which nothing calls at run time.
     */
    record Binding(Set<String> names, int firstLine, int lastLine, boolean typeOnly) {

        Binding {
            names = Collections.unmodifiableSet(new LinkedHashSet<>(names));
        }

        static Binding none(int line) {
            return new Binding(Set.of(), line, line, false);
        }
    }

    /** The import at {@code line} of {@code text} (for JavaScript, the line naming the module, as the scan records). */
    static Binding at(String language, String text, int line) {
        String[] lines = blanked(language, text).split("\n", -1);
        if (line < 1 || line > lines.length) {
            return Binding.none(line);
        }
        return switch (language) {
            case "npm" -> javascript(lines, line);
            case "pypi" -> python(lines, line);
            case "maven" -> jvm(lines[line - 1], line);
            default -> Binding.none(line);
        };
    }

    /**
     * The lines of {@code text} using what {@code binding} binds — outside imports and comments, not as a member — and,
     * one hop on, the lines using a variable or field it is assigned to or declared as ({@code private final
     * ObjectMapper objectMapper = new ObjectMapper()} → {@code objectMapper.writeValueAsString(…)}): what the code does
     * with a package is often on the variable, not the class.
     */
    static List<Integer> usages(String language, String text, Binding binding) {
        if (binding.names().isEmpty()) {
            return new ArrayList<>();
        }
        String[] lines = blanked(language, text).split("\n", -1);
        Set<Integer> found = matching(language, lines, binding, binding.names().stream()
                .map(name -> Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "(?![\\w$])")).toList());
        Set<String> held = new LinkedHashSet<>();
        for (int number : found) {
            held.addAll(held(lines[number - 1], binding.names()));
        }
        held.removeAll(binding.names());
        if (!held.isEmpty()) {
            found.addAll(matching(language, lines, binding, held.stream().limit(MAX_HELD)
                    .map(name -> Pattern.compile("(?<![\\w$.])(?:(?:this|self)\\.)?" + Pattern.quote(name) + "(?![\\w$])"))
                    .toList()));
        }
        return new ArrayList<>(found);
    }

    private static Set<Integer> matching(String language, String[] lines, Binding binding, List<Pattern> names) {
        Set<Integer> found = new TreeSet<>();
        for (int index = 0; index < lines.length; index++) {
            int number = index + 1;
            if (number >= binding.firstLine() && number <= binding.lastLine() || isImport(language, lines[index])) {
                continue;
            }
            String line = lines[index];
            if (names.stream().anyMatch(name -> name.matcher(line).find())) {
                found.add(number);
            }
        }
        return found;
    }

    /** The variables {@code line} assigns one of {@code names} to ({@code x = [new] Name…}) or declares as one. */
    private static Set<String> held(String line, Set<String> names) {
        Set<String> held = new LinkedHashSet<>();
        for (String name : names) {
            String quoted = Pattern.quote(name);
            Matcher assigned = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*(?::\\s*[\\w$.<>\\[\\], ?]+)?\\s*=\\s*"
                    + "(?:new\\s+|await\\s+)?(?<![\\w$.])" + quoted + "(?![\\w$])").matcher(line);
            while (assigned.find()) {
                held.add(assigned.group(1));
            }
            Matcher declared = Pattern.compile("(?<![\\w$.])" + quoted + "(?:<[^>]*>)?\\s+([a-z_$][\\w$]*)\\s*[;=,)]")
                    .matcher(line);
            while (declared.find()) {
                held.add(declared.group(1));
            }
        }
        held.removeAll(NOT_NAMES);
        return held;
    }

    /** Whether {@code line} imports something, in {@code language}. */
    static boolean isImport(String language, String line) {
        Pattern pattern = switch (language) {
            case "npm" -> JS_IMPORT_LINE;
            case "pypi" -> PY_IMPORT_LINE;
            case "maven" -> JVM_IMPORT_LINE;
            default -> null;
        };
        return pattern != null && pattern.matcher(line).find();
    }

    /** {@code text} with its comments blanked to spaces, every line where it was. */
    static String blanked(String language, String text) {
        String code = "pypi".equals(language) ? ImportScanner.withoutPythonComments(text)
                : ImportScanner.withoutCComments(text);
        return code.replace("\r", "");
    }

    // --- JavaScript and TypeScript ---------------------------------------------------------------------------------

    private static Binding javascript(String[] lines, int line) {
        int first = line;
        while (first > 1 && line - first < MAX_STATEMENT && !JS_START.matcher(lines[first - 1]).find()) {
            String previous = lines[first - 2].strip();
            if (previous.isEmpty() || previous.endsWith(";") || previous.equals("}")) {
                break;
            }
            first--;
        }
        String statement = String.join("\n", List.of(lines).subList(first - 1, line));
        Set<String> names = new LinkedHashSet<>();
        if (statement.stripLeading().startsWith("export")) {
            return new Binding(names, first, line, false); // a re-export: nothing bound here
        }
        Matcher from = JS_FROM.matcher(statement);
        if (from.find()) {
            if (from.group(1) != null) {
                return new Binding(names, first, line, true);
            }
            String clause = from.group(2);
            int open = clause.indexOf('{');
            int close = clause.lastIndexOf('}');
            String named = open >= 0 && close > open ? clause.substring(open + 1, close) : "";
            String rest = open >= 0 && close > open ? clause.substring(0, open) + clause.substring(close + 1) : clause;
            // The default or namespace import first, then the named ones, as the statement reads.
            for (String item : rest.split(",")) {
                String part = item.strip();
                if (part.startsWith("*")) {
                    add(names, alias(part, "\\s+as\\s+"), IDENTIFIER);
                } else if (!part.isEmpty()) {
                    add(names, part, IDENTIFIER);
                }
            }
            int types = 0;
            for (String item : named.split(",")) {
                String specifier = item.strip();
                if (specifier.startsWith("type ")) {
                    types++;
                } else if (!specifier.isEmpty()) {
                    add(names, alias(specifier, "\\s+as\\s+"), IDENTIFIER);
                }
            }
            return new Binding(names, first, line, types > 0 && names.isEmpty());
        }
        Matcher assigned = JS_ASSIGNED.matcher(statement);
        if (assigned.find()) {
            String target = assigned.group(1).strip();
            if (target.startsWith("{") && target.endsWith("}")) {
                for (String item : target.substring(1, target.length() - 1).split(",")) {
                    String part = item.strip().replaceFirst("^\\.\\.\\.", "");
                    part = part.contains(":") ? part.substring(part.indexOf(':') + 1) : part;
                    part = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
                    add(names, part.strip(), IDENTIFIER);
                }
            } else {
                add(names, target, IDENTIFIER);
            }
        }
        return new Binding(names, first, line, false);
    }

    // --- Python ----------------------------------------------------------------------------------------------------

    private static Binding python(String[] lines, int line) {
        StringBuilder statement = new StringBuilder(lines[line - 1]);
        int last = line;
        while (last < lines.length && last - line < MAX_STATEMENT && continues(statement.toString())) {
            String head = statement.toString().stripTrailing();
            statement.setLength(0);
            statement.append(head.endsWith("\\") ? head.substring(0, head.length() - 1) : head).append(' ')
                    .append(lines[last]);
            last++;
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher from = PY_FROM.matcher(statement);
        if (from.find()) {
            for (String item : from.group(1).replace("(", " ").replace(")", " ").split(",")) {
                String part = item.strip();
                if (!part.equals("*")) {
                    add(names, alias(part, "\\s+as\\s+"), PY_NAME);
                }
            }
            return new Binding(names, line, last, false);
        }
        Matcher plain = PY_IMPORT.matcher(statement);
        if (plain.find()) {
            for (String item : plain.group(1).split(",")) {
                add(names, alias(item.strip(), "\\s+as\\s+"), PY_NAME);
            }
        }
        return new Binding(names, line, last, false);
    }

    private static boolean continues(String statement) {
        return statement.stripTrailing().endsWith("\\")
                || statement.indexOf('(') >= 0 && statement.indexOf(')') < 0;
    }

    // --- Java and Kotlin -------------------------------------------------------------------------------------------

    private static Binding jvm(String text, int line) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = JVM_IMPORT.matcher(text);
        if (matcher.matches() && matcher.group(3) == null) {
            String path = matcher.group(2);
            names.add(matcher.group(4) != null ? matcher.group(4) : path.substring(path.lastIndexOf('.') + 1));
        }
        return new Binding(names, line, line, false);
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** {@code a as b} → {@code b}; {@code a} → {@code a}. */
    private static String alias(String specifier, String as) {
        String[] parts = specifier.split(as);
        return parts[parts.length - 1].strip();
    }

    private static void add(Set<String> names, String name, Pattern valid) {
        if (valid.matcher(name).matches() && !name.equals("default")) {
            names.add(name);
        }
    }
}
