package com.aegis.core.inventory.sbom;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.aegis.core.platform.common.ApiExceptions;

/**
 * A parsed package URL ({@code pkg:type/namespace/name@version?qualifiers#subpath}). Qualifiers and
 * subpath do not identify a component in the inventory, so they are dropped.
 */
public record PackageUrl(String type, String namespace, String name, String version) {

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9.+-]*");
    private static final int MAX_ECHOED_LENGTH = 100;

    public static PackageUrl parse(String value) {
        String rest = value.trim();
        if (!rest.regionMatches(true, 0, "pkg:", 0, 4)) {
            throw invalid(value);
        }
        rest = stripLeadingSlashes(rest.substring(4));
        rest = cutAt(cutAt(rest, '#'), '?');

        int typeEnd = rest.indexOf('/');
        if (typeEnd <= 0) {
            throw invalid(value);
        }
        String type = rest.substring(0, typeEnd).toLowerCase(Locale.ROOT);
        if (!TYPE.matcher(type).matches()) {
            throw invalid(value);
        }

        String path = rest.substring(typeEnd + 1);
        String version = null;
        // A scoped npm name may carry a raw '@' ("@scope/name"); only an '@' after the last '/' is the version.
        int at = path.lastIndexOf('@');
        if (at > path.lastIndexOf('/')) {
            version = decode(path.substring(at + 1), value);
            path = path.substring(0, at);
        }

        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(decode(segment, value));
            }
        }
        if (segments.isEmpty() || segments.getLast().isBlank()) {
            throw invalid(value);
        }
        String name = segments.removeLast();
        String namespace = segments.isEmpty() ? null : String.join("/", segments);
        return new PackageUrl(type, namespace, name, version == null || version.isBlank() ? null : version);
    }

    /** The package name as advisory feeds (OSV) spell it: {@code group:artifact} for Maven, else {@code ns/name}. */
    public String packageName() {
        if (namespace == null) {
            return name;
        }
        return "maven".equals(type) ? namespace + ":" + name : namespace + "/" + name;
    }

    private static String stripLeadingSlashes(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) == '/') {
            start++;
        }
        return value.substring(start);
    }

    private static String cutAt(String value, char separator) {
        int index = value.indexOf(separator);
        return index < 0 ? value : value.substring(0, index);
    }

    private static String decode(String segment, String original) {
        try {
            // URLDecoder treats '+' as a space; in a purl it is a literal plus.
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw invalid(original);
        }
    }

    private static ApiExceptions.BadRequest invalid(String value) {
        String shown = value.length() > MAX_ECHOED_LENGTH ? value.substring(0, MAX_ECHOED_LENGTH) + "…" : value;
        return new ApiExceptions.BadRequest("Invalid package URL: " + shown);
    }
}
