package com.aegis.core.audit;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.aegis.core.platform.common.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Evidence export (E10 story 3): a service's decision trail for a period, as a PDF to hand an auditor or regulator or
 * as CSV to work with, both holding the same records. Read-only, for the same people who read the trail.
 */
@RestController
@RequestMapping("/api/audit/evidence")
@SecurityRequirement(name = "bearer-jwt")
public class EvidenceController {

    /** The header carrying the SHA-256 of the CSV of the exported records, which the PDF prints too. */
    static final String CSV_SHA256 = "X-Evidence-Csv-Sha256";
    /** The header carrying how many records the export holds. */
    static final String RECORDS = "X-Evidence-Records";

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final EvidenceExport export;
    private final ObjectMapper mapper;

    public EvidenceController(EvidenceExport export, ObjectMapper mapper) {
        this.export = export;
        this.mapper = mapper;
    }

    @GetMapping
    @PreAuthorize(DecisionTrailController.READ)
    @Operation(summary = "Export a service's evidence for a period: every decision record covering the service taken "
            + "in [from, to) — its risk tickets, upgrade pull requests and the regulator memos covering it — oldest "
            + "first, with who took each step, why, the impact then and the response as it stood. format=csv (every "
            + "field, UTF-8, the snapshots as JSON) or pdf (to read, times in `zone`). Both carry the CSV's SHA-256 "
            + "(header X-Evidence-Csv-Sha256; printed in the PDF). At most 5,000 records per export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) UUID serviceId,
            @Parameter(description = "Taken at or after this instant (ISO-8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Taken before this instant (ISO-8601, exclusive)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "csv or pdf") @RequestParam(defaultValue = "csv") String format,
            @Parameter(description = "The time zone a PDF shows times in (IANA, e.g. Asia/Singapore)")
            @RequestParam(defaultValue = "UTC") String zone,
            Authentication authentication) {
        boolean pdf = switch (format.toLowerCase(Locale.ROOT)) {
            case "csv" -> false;
            case "pdf" -> true;
            default -> throw new ApiExceptions.BadRequest("format is csv or pdf");
        };
        ZoneId readerZone = zone(zone);
        EvidenceExport.Evidence evidence = export.collect(serviceId, from, to);
        byte[] csv = EvidenceCsv.write(evidence);
        String digest = EvidenceCsv.sha256(csv);

        byte[] body = csv;
        MediaType type = CSV;
        if (pdf) {
            body = EvidencePdf.write(evidence,
                    new EvidencePdf.Cover(generatedBy(authentication), Instant.now(), readerZone, digest), mapper);
            type = MediaType.APPLICATION_PDF;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName(evidence, readerZone, pdf ? "pdf" : "csv"))
                        .build().toString())
                .header(CSV_SHA256, digest)
                .header(RECORDS, Integer.toString(evidence.records().size()))
                .body(body);
    }

    /** Who is exporting, by name where the directory knows them. */
    private String generatedBy(Authentication authentication) {
        try {
            UUID caller = UUID.fromString(authentication.getName());
            return export.namesOf(List.of(caller)).getOrDefault(caller, "user " + caller);
        } catch (IllegalArgumentException notAUser) {
            return authentication.getName();
        }
    }

    private static ZoneId zone(String zone) {
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException unknown) {
            throw new ApiExceptions.BadRequest("Unknown time zone: " + zone);
        }
    }

    /** e.g. {@code aegis-evidence-payments-api-2026-10-01-to-2026-10-31.pdf}: the service and the days covered. */
    static String fileName(EvidenceExport.Evidence evidence, ZoneId zone, String extension) {
        String slug = evidence.serviceName() == null ? "" : evidence.serviceName().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) {
            slug = "service";
        } else if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        LocalDate first = evidence.from().atZone(zone).toLocalDate();
        LocalDate last = evidence.to().minusNanos(1).atZone(zone).toLocalDate();
        return "aegis-evidence-" + slug + "-" + first + "-to-" + last + "." + extension;
    }
}
