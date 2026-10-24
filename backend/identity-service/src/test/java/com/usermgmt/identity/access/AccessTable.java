package com.usermgmt.identity.access;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The access-control table in {@code security/access-control.md} (CS203-147): one section per service, one row per
 * endpoint, one column per caller. The access tests read it, so the document and the tests can't drift apart.
 */
final class AccessTable {

    /** The repository's copy, read from the module's directory (Maven runs tests there). */
    static final Path FILE = Path.of("..", "..", "security", "access-control.md");

    /** The callers the table has a column for, in the table's order. */
    static final List<String> CALLERS = List.of("Anonymous", "USER", "SERVICE_OWNER", "ANALYST", "COMPLIANCE_ADMIN",
            "ORG_ADMIN", "PLATFORM_ADMIN");

    /** One endpoint: its method, its path as the code declares it, and each caller's access. */
    record Row(String method, String path, Map<String, String> access) {

        String key() {
            return method + " " + path;
        }
    }

    private AccessTable() {
    }

    /** The rows of the table under the {@code ## heading} section. */
    static List<Row> section(String heading) {
        List<String> lines;
        try {
            lines = Files.readAllLines(FILE);
        } catch (IOException missing) {
            throw new UncheckedIOException("The access-control table is missing: " + FILE.toAbsolutePath(), missing);
        }
        List<Row> rows = new ArrayList<>();
        List<String> columns = null;
        boolean inSection = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                inSection = line.substring(3).strip().equals(heading);
                columns = null;
                continue;
            }
            if (!inSection || !line.startsWith("|")) {
                continue;
            }
            List<String> cells = cells(line);
            if (columns == null) {
                columns = cells;
            } else if (!cells.get(0).startsWith("---")) {
                Map<String, String> access = new LinkedHashMap<>();
                for (String caller : CALLERS) {
                    int index = columns.indexOf(caller);
                    if (index < 0) {
                        throw new IllegalStateException("The " + heading + " table has no column for " + caller);
                    }
                    access.put(caller, cells.get(index));
                }
                int extra = columns.indexOf("Service token");
                if (extra >= 0) {
                    access.put("Service token", cells.get(extra));
                }
                rows.add(new Row(cells.get(0), cells.get(1).replace("`", ""), access));
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("No table under \"## " + heading + "\" in " + FILE);
        }
        return rows;
    }

    private static List<String> cells(String line) {
        String inner = line.strip();
        inner = inner.substring(1, inner.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : inner.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }
}
