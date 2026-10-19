package com.aegis.core.actions.upgrade;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aegis.core.platform.common.ApiExceptions;

/**
 * Bumps a dependency in a {@code pom.xml}: the {@code <version>} of every {@code <dependency>} naming
 * {@code groupId:artifactId} at the installed version, or the {@code <properties>} entry that version
 * reads from ({@code ${log4j.version}}) — set in this pom when only a parent defines it, which the pom's own value
 * then overrides. A dependency the pom declares without a version (a parent POM or BOM manages it) is pinned in the
 * pom's own {@code <dependencyManagement>}, which wins over what it inherits.
 *
 * <p>A dependency the pom doesn't declare at all — another one pulls it in — is overridden the same way
 * ({@link #override}): an entry in {@code <dependencyManagement>} sets the version Maven resolves for it everywhere in
 * the module. A declaration at another version, or a managed entry that reads its version from elsewhere, is refused.
 */
final class MavenPomEditor implements ManifestEditor {

    private static final Pattern DEPENDENCY = Pattern.compile("<dependency\\b[^>]*>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern EXCLUSIONS = Pattern.compile("<exclusions\\b.*?</exclusions>", Pattern.DOTALL);
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<]*?)\\s*</version>");
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([A-Za-z0-9._-]+)}");
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z_][\\w.:-]*)(?:\\s[^>]*?)?(/?)>");
    private static final Pattern NOT_MARKUP = Pattern.compile("<!--.*?-->|<!\\[CDATA\\[.*?]]>|<\\?.*?\\?>", Pattern.DOTALL);

    @Override
    public String fileName() {
        return "pom.xml";
    }

    @Override
    public String upgrade(String pom, String packageName, String fromVersion, String toVersion) {
        String[] coordinates = coordinates(packageName);
        String groupId = coordinates[0];
        String artifactId = coordinates[1];

        StringBuilder rewritten = new StringBuilder();
        Set<String> properties = new LinkedHashSet<>();
        List<String> otherVersions = new ArrayList<>();
        int declared = 0;
        int updated = 0;
        int copied = 0;
        Matcher dependency = DEPENDENCY.matcher(pom);
        while (dependency.find()) {
            // An <exclusion> also has a groupId and artifactId; only the dependency's own count.
            String own = EXCLUSIONS.matcher(dependency.group(1)).replaceAll("");
            if (!groupId.equals(element(own, "groupId")) || !artifactId.equals(element(own, "artifactId"))) {
                continue;
            }
            declared++;
            Matcher version = VERSION.matcher(own);
            if (!version.find()) {
                continue; // versionless: a parent POM or BOM manages it
            }
            String value = version.group(1);
            Matcher property = PROPERTY_REFERENCE.matcher(value);
            if (value.equals(fromVersion)) {
                // Exclusions carry no <version>, so the first one in the block is the dependency's own.
                Matcher inBlock = VERSION.matcher(dependency.group());
                inBlock.find();
                rewritten.append(pom, copied, dependency.start() + inBlock.start(1)).append(toVersion);
                copied = dependency.start() + inBlock.end(1);
                updated++;
            } else if (property.matches()) {
                properties.add(property.group(1));
            } else {
                otherVersions.add(value);
            }
        }
        rewritten.append(pom, copied, pom.length());
        String result = rewritten.toString();

        for (String property : properties) {
            Pattern definition = Pattern.compile(
                    "(<" + Pattern.quote(property) + ">)\\s*" + Pattern.quote(fromVersion) + "\\s*(</" + Pattern.quote(property) + ">)");
            Matcher found = definition.matcher(result);
            if (found.find()) {
                result = found.replaceAll("$1" + Matcher.quoteReplacement(toVersion) + "$2");
            } else if (defines(result, property) || builtIn(property)) {
                throw new ApiExceptions.Conflict("pom.xml takes the version of " + packageName + " from ${" + property
                        + "}, which it does not set to " + fromVersion);
            } else {
                // A parent POM defines it: this pom's own value overrides the inherited one.
                result = setProperty(result, property, toVersion);
            }
            updated++;
        }

        if (declared == 0) {
            throw new ApiExceptions.Conflict(packageName + " is not declared in pom.xml, so there is no version to "
                    + "bump; re-import the service's SBOM");
        }
        if (updated == 0 && otherVersions.isEmpty()) {
            // Declared without a version: a parent POM or BOM manages it, and this pom's own management wins.
            return manage(result, groupId, artifactId, toVersion);
        }
        if (updated == 0) {
            throw new ApiExceptions.Conflict("pom.xml declares " + packageName + " at " + String.join(", ", otherVersions)
                    + ", not " + fromVersion + " — re-import the service's SBOM");
        }
        return result;
    }

    @Override
    public String override(String pom, String packageName, String fromVersion, String toVersion, String via) {
        String[] coordinates = coordinates(packageName);
        if (declaresOutsideManagement(pom, coordinates[0], coordinates[1])) {
            return upgrade(pom, packageName, fromVersion, toVersion);
        }
        return manage(pom, coordinates[0], coordinates[1], toVersion);
    }

    /**
     * The pom with {@code groupId:artifactId} managed at {@code version} in its own {@code <dependencyManagement>}:
     * an entry's literal version replaced, or a new entry added — and the section itself, before the pom's
     * dependencies, when it has none.
     */
    private static String manage(String pom, String groupId, String artifactId, String version) {
        List<Tag> tags = project(pom);
        Element management = child(tags, "dependencyManagement", 1, 0, pom.length());
        String unit = unit(pom, tags);
        String newline = pom.contains("\r\n") ? "\r\n" : "\n";
        boolean multiline = pom.contains("\n");
        if (management != null) {
            Element dependencies = child(tags, "dependencies", 2, management.openEnd(), management.closeStart());
            if (dependencies == null || dependencies.selfClosing()) {
                throw new ApiExceptions.Conflict("pom.xml's <dependencyManagement> lists no dependencies; manage "
                        + groupId + ":" + artifactId + " at " + version + " there by hand");
            }
            Matcher entry = DEPENDENCY.matcher(pom).region(dependencies.openEnd(), dependencies.closeStart());
            while (entry.find()) {
                String own = EXCLUSIONS.matcher(entry.group(1)).replaceAll("");
                if (!groupId.equals(element(own, "groupId")) || !artifactId.equals(element(own, "artifactId"))) {
                    continue;
                }
                Matcher current = VERSION.matcher(entry.group());
                if (!current.find() || PROPERTY_REFERENCE.matcher(current.group(1)).find()) {
                    throw new ApiExceptions.Conflict("pom.xml already manages " + groupId + ":" + artifactId
                            + " with a version set elsewhere; raise it to " + version + " there by hand");
                }
                if (current.group(1).equals(version)) {
                    throw new ApiExceptions.Conflict("pom.xml already manages " + groupId + ":" + artifactId + " at "
                            + version);
                }
                return pom.substring(0, entry.start() + current.start(1)) + escape(version)
                        + pom.substring(entry.start() + current.end(1));
            }
            String indent = multiline ? lineIndent(pom, dependencies.closeStart()) + unit : "";
            return insertBefore(pom, dependencies.closeStart(),
                    dependency(groupId, artifactId, version, indent, unit(multiline, unit), multiline ? newline : ""));
        }
        Element dependencies = child(tags, "dependencies", 1, 0, pom.length());
        int anchor;
        String base;
        if (dependencies != null) {
            anchor = dependencies.openStart();
            base = multiline ? lineIndent(pom, anchor) : "";
        } else {
            Tag project = tags.stream().filter(tag -> tag.closing() && tag.depth() == 0).reduce((a, b) -> b)
                    .orElseThrow(() -> new ApiExceptions.Conflict("pom.xml has no <project> element"));
            anchor = project.start();
            base = multiline ? lineIndent(pom, anchor) + unit : "";
        }
        String nl = multiline ? newline : "";
        String block = base + "<dependencyManagement>" + nl
                + base + unit(multiline, unit) + "<dependencies>" + nl
                + dependency(groupId, artifactId, version, base + unit(multiline, unit) + unit(multiline, unit),
                        unit(multiline, unit), nl)
                + base + unit(multiline, unit) + "</dependencies>" + nl
                + base + "</dependencyManagement>" + nl;
        return insertBefore(pom, anchor, block);
    }

    /** One {@code <dependency>} element, each line at {@code indent} (its children one {@code unit} further). */
    private static String dependency(String groupId, String artifactId, String version, String indent, String unit,
            String newline) {
        return indent + "<dependency>" + newline
                + indent + unit + "<groupId>" + escape(groupId) + "</groupId>" + newline
                + indent + unit + "<artifactId>" + escape(artifactId) + "</artifactId>" + newline
                + indent + unit + "<version>" + escape(version) + "</version>" + newline
                + indent + "</dependency>" + newline;
    }

    /** {@code property} set to {@code value} in the pom's {@code <properties>}, added before what follows when absent. */
    private static String setProperty(String pom, String property, String value) {
        List<Tag> tags = project(pom);
        String unit = unit(pom, tags);
        String newline = pom.contains("\r\n") ? "\r\n" : "\n";
        boolean multiline = pom.contains("\n");
        String nl = multiline ? newline : "";
        String line = "<" + property + ">" + escape(value) + "</" + property + ">";
        Element properties = child(tags, "properties", 1, 0, pom.length());
        if (properties != null && !properties.selfClosing()) {
            String indent = multiline ? lineIndent(pom, properties.closeStart()) + unit : "";
            return insertBefore(pom, properties.closeStart(), indent + line + nl);
        }
        if (properties != null) {
            throw new ApiExceptions.Conflict("pom.xml has an empty <properties/>; set " + property + " to " + value
                    + " there by hand");
        }
        Element next = child(tags, "dependencyManagement", 1, 0, pom.length());
        if (next == null) {
            next = child(tags, "dependencies", 1, 0, pom.length());
        }
        int anchor;
        String base;
        if (next != null) {
            anchor = next.openStart();
            base = multiline ? lineIndent(pom, anchor) : "";
        } else {
            Tag project = tags.stream().filter(tag -> tag.closing() && tag.depth() == 0).reduce((a, b) -> b)
                    .orElseThrow(() -> new ApiExceptions.Conflict("pom.xml has no <project> element"));
            anchor = project.start();
            base = multiline ? lineIndent(pom, anchor) + unit : "";
        }
        return insertBefore(pom, anchor, base + "<properties>" + nl + base + unit(multiline, unit) + line + nl
                + base + "</properties>" + nl);
    }

    /** Whether the pom declares the dependency itself, in its dependencies rather than only managing it. */
    private static boolean declaresOutsideManagement(String pom, String groupId, String artifactId) {
        List<Tag> tags = tags(pom);
        Element management = child(tags, "dependencyManagement", 1, 0, pom.length());
        Matcher dependency = DEPENDENCY.matcher(pom);
        while (dependency.find()) {
            if (management != null && dependency.start() > management.openStart()
                    && dependency.start() < management.closeStart()) {
                continue;
            }
            String own = EXCLUSIONS.matcher(dependency.group(1)).replaceAll("");
            if (groupId.equals(element(own, "groupId")) && artifactId.equals(element(own, "artifactId"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean defines(String pom, String property) {
        return Pattern.compile("<" + Pattern.quote(property) + ">").matcher(pom).find();
    }

    /** Properties Maven sets itself, which no pom overrides by defining them. */
    private static boolean builtIn(String property) {
        return property.startsWith("project.") || property.startsWith("pom.") || property.startsWith("env.")
                || property.startsWith("settings.") || property.startsWith("maven.");
    }

    private static String[] coordinates(String packageName) {
        int colon = packageName.indexOf(':');
        if (colon <= 0 || colon == packageName.length() - 1 || packageName.indexOf(':', colon + 1) >= 0) {
            throw new ApiExceptions.Conflict("A Maven component is named groupId:artifactId, not " + packageName);
        }
        return new String[] {packageName.substring(0, colon), packageName.substring(colon + 1)};
    }

    /** The trimmed text of the first {@code <name>} element, or null. */
    private static String element(String xml, String name) {
        Matcher matcher = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
    }

    // --- the pom's structure, enough to place an element where Maven reads it -------------------------------------

    /** A tag outside comments, CDATA and processing instructions, and how deep it opens ({@code <project>} is 0). */
    private record Tag(String name, int start, int end, boolean closing, boolean selfClosing, int depth) {
    }

    /** An element: its opening tag, and its closing tag's start (its opening tag's end when self-closing). */
    private record Element(int openStart, int openEnd, int closeStart, boolean selfClosing) {
    }

    private static List<Tag> tags(String pom) {
        StringBuilder masked = new StringBuilder(pom);
        Matcher hidden = NOT_MARKUP.matcher(pom);
        while (hidden.find()) {
            for (int at = hidden.start(); at < hidden.end(); at++) {
                masked.setCharAt(at, pom.charAt(at) == '\n' ? '\n' : ' ');
            }
        }
        List<Tag> tags = new ArrayList<>();
        int depth = 0;
        Matcher tag = TAG.matcher(masked);
        while (tag.find()) {
            boolean closing = !tag.group(1).isEmpty();
            boolean selfClosing = !tag.group(3).isEmpty();
            if (closing) {
                depth--;
                tags.add(new Tag(tag.group(2), tag.start(), tag.end(), true, false, depth));
            } else {
                tags.add(new Tag(tag.group(2), tag.start(), tag.end(), false, selfClosing, depth));
                if (!selfClosing) {
                    depth++;
                }
            }
        }
        return tags;
    }

    /** The pom's tags, when its document element is {@code <project>}: where a new element can go is known. */
    private static List<Tag> project(String pom) {
        List<Tag> tags = tags(pom);
        boolean project = !tags.isEmpty() && !tags.getFirst().closing() && tags.getFirst().depth() == 0
                && tags.getFirst().name().equals("project")
                && tags.stream().anyMatch(tag -> tag.closing() && tag.depth() == 0 && tag.name().equals("project"));
        if (!project) {
            throw new ApiExceptions.Conflict("pom.xml has no <project> element to add to");
        }
        return tags;
    }

    /** The first {@code name} element opening at {@code depth} between {@code from} and {@code to}, or null. */
    private static Element child(List<Tag> tags, String name, int depth, int from, int to) {
        for (int at = 0; at < tags.size(); at++) {
            Tag open = tags.get(at);
            if (open.closing() || open.depth() != depth || !open.name().equals(name) || open.start() < from
                    || open.start() >= to) {
                continue;
            }
            if (open.selfClosing()) {
                return new Element(open.start(), open.end(), open.end(), true);
            }
            for (int next = at + 1; next < tags.size(); next++) {
                Tag close = tags.get(next);
                if (close.closing() && close.depth() == depth && close.name().equals(name)) {
                    return new Element(open.start(), open.end(), close.start(), false);
                }
            }
            return null;
        }
        return null;
    }

    /** The pom's indentation step: how far its first element under {@code <project>} is indented; else four spaces. */
    private static String unit(String pom, List<Tag> tags) {
        return tags.stream().filter(tag -> !tag.closing() && tag.depth() == 1).findFirst()
                .map(tag -> lineIndent(pom, tag.start()))
                .filter(indent -> !indent.isEmpty())
                .orElse("    ");
    }

    private static String unit(boolean multiline, String unit) {
        return multiline ? unit : "";
    }

    /** The whitespace before {@code at} on its line, when only whitespace is; else nothing. */
    private static String lineIndent(String text, int at) {
        int lineStart = text.lastIndexOf('\n', at - 1) + 1;
        String before = text.substring(lineStart, at);
        return before.isBlank() ? before : "";
    }

    /** {@code block} inserted where the line holding {@code at} starts, when only whitespace precedes it; else at it. */
    private static String insertBefore(String text, int at, String block) {
        int lineStart = text.lastIndexOf('\n', at - 1) + 1;
        int position = text.substring(lineStart, at).isBlank() && text.contains("\n") ? lineStart : at;
        return text.substring(0, position) + block + text.substring(position);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
