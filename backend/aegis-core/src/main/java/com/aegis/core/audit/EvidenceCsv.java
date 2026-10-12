package com.aegis.core.audit;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A service's evidence as CSV (E10 story 3): one row per decision record, oldest first, with every field the trail
 * holds, the snapshot ({@code state}, the response as it stood then) included as JSON. RFC 4180 (every field quoted,
 * CRLF line ends), UTF-8 with a byte-order mark so a spreadsheet reads it as UTF-8. Written as the records are read,
 * so a large export is never held in memory, with its SHA-256 computed on the way out.
 *
 * <p>Every field is kept as recorded, with one exception. Change titles and reasons come partly from outside
 * (advisory feeds, what people typed), so a field a spreadsheet would run as a formula ({@code =}, {@code +},
 * {@code -}, {@code @}, a tab or carriage return first) is written with a {@code '} before it. The bytes depend only
 * on the records and the names, never on when the export was made.
 */
public final class EvidenceCsv {

    public static final List<String> COLUMNS = List.of("record_id", "sequence", "occurred_at", "action", "subject",
            "subject_id", "response_id", "service_id", "service_name", "service_ids", "change_signal_id",
            "change_title", "actor_id", "actor_name", "reason", "score", "tier", "state");

    private static final String BOM = "﻿";
    private static final String EOL = "\r\n";

    private EvidenceCsv() {
    }

    /** What was written: how many rows, the SHA-256 of every byte, and the latest service name a record copied. */
    public record Written(int rows, String sha256, String lastServiceName) {
    }

    /** Write {@code records} to {@code out} (left open) as CSV, people named by {@code people}. */
    public static Written write(EvidenceExport.Records records, EvidenceExport.People people, OutputStream out)
            throws IOException {
        MessageDigest digest = sha256();
        Writer csv = new BufferedWriter(new OutputStreamWriter(new DigestOutputStream(out, digest),
                StandardCharsets.UTF_8));
        csv.write(BOM);
        row(csv, COLUMNS);
        int[] rows = {0};
        String[] lastServiceName = {null};
        records.forEach(record -> {
            try {
                row(csv, fields(record, people));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            rows[0]++;
            if (record.serviceName() != null) {
                lastServiceName[0] = record.serviceName();
            }
        });
        csv.flush();
        return new Written(rows[0], HexFormat.of().formatHex(digest.digest()), lastServiceName[0]);
    }

    /** The SHA-256 of these bytes, in hex: what anyone can recompute from a CSV. */
    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static List<String> fields(DecisionRecord record, EvidenceExport.People people) {
        return List.of(
                record.id().toString(),
                Long.toString(record.sequence()),
                record.occurredAt().toString(),
                record.action().name(),
                record.subject().name(),
                record.subjectId().toString(),
                record.responseId().toString(),
                text(record.serviceId()),
                text(record.serviceName()),
                record.serviceIds().stream().map(UUID::toString).collect(Collectors.joining(" ")),
                record.changeSignalId().toString(),
                text(record.changeTitle()),
                record.actorId().toString(),
                people.nameOf(record.actorId()),
                text(record.reason()),
                record.score() == null ? "" : record.score().toString(),
                record.tier() == null ? "" : record.tier().name(),
                text(record.state()));
    }

    private static void row(Writer csv, List<String> fields) throws IOException {
        csv.write(fields.stream().map(EvidenceCsv::field).collect(Collectors.joining(",")));
        csv.write(EOL);
    }

    /** One field, quoted, its quotes doubled, and made inert if a spreadsheet would run it as a formula. */
    static String field(String value) {
        String safe = value;
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            safe = "'" + value;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every Java platform provides SHA-256", impossible);
        }
    }
}
