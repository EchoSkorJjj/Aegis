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
 * reads from ({@code ${log4j.version}}). A dependency the pom does not declare (it is transitive, or a
 * parent POM or BOM manages it) or declares at another version is refused.
 */
final class MavenPomEditor implements ManifestEditor {

    private static final Pattern DEPENDENCY = Pattern.compile("<dependency\\b[^>]*>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern EXCLUSIONS = Pattern.compile("<exclusions\\b.*?</exclusions>", Pattern.DOTALL);
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<]*?)\\s*</version>");
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([A-Za-z0-9._-]+)}");

    @Override
    public String fileName() {
        return "pom.xml";
    }

    @Override
    public String upgrade(String pom, String packageName, String fromVersion, String toVersion) {
        int colon = packageName.indexOf(':');
        if (colon <= 0 || colon == packageName.length() - 1 || packageName.indexOf(':', colon + 1) >= 0) {
            throw new ApiExceptions.Conflict("A Maven component is named groupId:artifactId, not " + packageName);
        }
        String groupId = packageName.substring(0, colon);
        String artifactId = packageName.substring(colon + 1);

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
            if (!found.find()) {
                throw new ApiExceptions.Conflict("pom.xml takes the version of " + packageName + " from ${" + property
                        + "}, which it does not set to " + fromVersion + " (a parent POM may define it)");
            }
            result = found.replaceAll("$1" + Matcher.quoteReplacement(toVersion) + "$2");
            updated++;
        }

        if (declared == 0) {
            throw new ApiExceptions.Conflict(packageName + " is not declared in pom.xml: it comes in transitively or "
                    + "from a parent POM or BOM, which automated upgrades do not edit yet");
        }
        if (updated == 0) {
            throw new ApiExceptions.Conflict(otherVersions.isEmpty()
                    ? "pom.xml declares " + packageName + " without a version: a parent POM or BOM manages it"
                    : "pom.xml declares " + packageName + " at " + String.join(", ", otherVersions) + ", not "
                            + fromVersion + " — re-import the service's SBOM");
        }
        return result;
    }

    /** The trimmed text of the first {@code <name>} element, or null. */
    private static String element(String xml, String name) {
        Matcher matcher = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
    }
}
