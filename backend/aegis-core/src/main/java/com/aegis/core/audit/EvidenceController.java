package com.aegis.core.audit;

import java.io.IOException;
import java.nio.file.Files;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import com.aegis.core.platform.common.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Evidence export (E10 story 3): a service's decision trail for a period, as a PDF to hand an auditor or regulator
 * (carrying the CSV of the same records attached) or as CSV to work with. Read-only, for the same people who read the
 * trail. The file is prepared on disk, then sent: an export that can't be made answers with the usual error, never a
 * broken file.
 */
@RestController
@RequestMapping("/api/audit/evidence")
@SecurityRequirement(name = "bearer-jwt")
public class EvidenceController {

    /** The header carrying the SHA-256 of the CSV of the exported records: the CSV sent, or the one a PDF attaches. */
    static final String CSV_SHA256 = "X-Evidence-Csv-Sha256";
    /** The header carrying how many records the export holds. */
    static final String RECORDS = "X-Evidence-Records";

    private final EvidenceExport export;

    public EvidenceController(EvidenceExport export) {
        this.export = export;
    }

    @GetMapping
    @PreAuthorize(DecisionTrailController.READ)
    @Operation(summary = "Export a service's evidence for a period: every decision record covering the service taken "
            + "in [from, to) — its risk tickets, upgrade pull requests and the regulator memos covering it — oldest "
            + "first, with who took each step, why, the impact then and the response as it stood, read at one moment. "
            + "format=csv (every field, UTF-8, the snapshots as JSON; a field a spreadsheet would run as a formula "
            + "starts with ') or pdf (to read, times in `zone`, the CSV of the same records attached and its SHA-256 "
            + "printed). Header X-Evidence-Csv-Sha256 is that CSV's SHA-256. At most 5,000 records per CSV, 2,000 per "
            + "PDF; 429 while other exports are being prepared; 502 if the member directory can't be reached",
            responses = @ApiResponse(responseCode = "200", content = {
                    @Content(mediaType = "text/csv"), @Content(mediaType = "application/pdf")}))
    public void export(
            @RequestParam(required = false) UUID serviceId,
            @Parameter(description = "Taken at or after this instant (ISO-8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Taken before this instant (ISO-8601, exclusive)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "csv or pdf") @RequestParam(defaultValue = "csv") String format,
            @Parameter(description = "The time zone a PDF shows times in (IANA, e.g. Asia/Singapore)")
            @RequestParam(defaultValue = "UTC") String zone,
            Authentication authentication,
            HttpServletResponse response) throws IOException {
        try (EvidenceExport.ExportFile file = export.export(serviceId, from, to, format, zone(zone),
                authentication.getName())) {
            response.setContentType(file.format().contentType());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(file.fileName()).build().toString());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(CSV_SHA256, file.csvSha256());
            response.setHeader(RECORDS, Integer.toString(file.records()));
            response.setContentLengthLong(Files.size(file.path()));
            Files.copy(file.path(), response.getOutputStream());
        }
    }

    private static ZoneId zone(String zone) {
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException unknown) {
            throw new ApiExceptions.BadRequest("Unknown time zone: " + zone);
        }
    }
}
