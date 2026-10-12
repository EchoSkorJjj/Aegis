package com.aegis.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A service's evidence as CSV (E10 story 3): one row per decision record, oldest first, with every field the trail
 * holds, the snapshot ({@code state}, the response as it stood then) included as JSON. RFC 4180 (every field quoted,
 * CRLF line ends), UTF-8 with a byte-order mark so a spreadsheet reads it as UTF-8.
 *
 * <p>Change titles and reasons come partly from outside (advisory feeds, what people typed), so a field a
 * spreadsheet would run as a formula ({@code =}, {@code +}, {@code -}, {@code @}, a tab or carriage return first) is
 * written with a {@code '} before it. The bytes depend only on the records and the names, never on when the export was
 * made, so the same evidence always has the same {@linkplain #sha256 digest}, which the PDF prints.
 */
public final class EvidenceCsv {

    public static final List<String> COLUMNS = List.of("record_id", "sequence", "occurred_at", "action", "subject",
            "subject_id", "response_id", "service_id", "service_name", "service_ids", "change_signal_id",
            "change_title", "actor_id", "actor_name", "reason", "score", "tier", "state");

    private static final String BOM = "﻿";
    private static final String EOL = "\r\n";

    private EvidenceCsv() {
    }

    public static byte[] write(EvidenceExport.Evidence evidence) {
        StringBuilder csv = new StringBuilder(BOM);
        row(csv, COLUMNS);
        for (DecisionRecord record : evidence.records()) {
            row(csv, List.of(
                    record.id().toString(),
                    Long.toString(record.sequence()),
                    record.occurredAt().toString(),
                    record.action().name(),
                    record.subject().name(),
                    record.subjectId().toString(),
                    record.responseId().toString(),
                    text(record.serviceId()),
                    text(record.serviceName()),
                    record.serviceIds().stream().map(Object::toString).collect(Collectors.joining(" ")),
                    record.changeSignalId().toString(),
                    text(record.changeTitle()),
                    record.actorId().toString(),
                    evidence.nameOf(record.actorId()),
                    text(record.reason()),
                    record.score() == null ? "" : record.score().toString(),
                    record.tier() == null ? "" : record.tier().name(),
                    text(record.state())));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The SHA-256 of an export's bytes, in hex: what the PDF prints, and what anyone can recompute from the CSV. */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every Java platform provides SHA-256", impossible);
        }
    }

    private static void row(StringBuilder csv, List<String> fields) {
        csv.append(fields.stream().map(EvidenceCsv::field).collect(Collectors.joining(","))).append(EOL);
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
}
