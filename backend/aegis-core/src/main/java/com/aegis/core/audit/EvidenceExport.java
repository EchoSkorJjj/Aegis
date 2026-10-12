package com.aegis.core.audit;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.common.SensitiveText;
import com.aegis.core.platform.identity.DirectoryUser;
import com.aegis.core.platform.identity.IdentityDirectory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The evidence for one service and period (E10 story 3): every record in the service's decision trail taken in
 * {@code [from, to)} — its tickets', its pull requests' and the regulator memos covering it — oldest first, each with the
 * response as it stood then, written as CSV ({@link EvidenceCsv}) or as a PDF ({@link EvidencePdf}) that carries that
 * CSV attached and prints its SHA-256.
 *
 * <p>An export is one reading of the trail: every page is read in one read-only transaction at repeatable-read
 * isolation, so a decision committed meanwhile can neither shift a page (a record skipped, another repeated) nor make
 * the PDF and its attached CSV disagree. People are named first, from the whole member directory; if it can't be
 * reached the export is refused rather than written with some names missing. Files are written to disk a page at a
 * time, never held whole in memory, and only a few exports run at once.
 */
@Service
public class EvidenceExport {

    /** More records than this in one CSV is refused: a period that large is better split. */
    static final int MAX_RECORDS = 5_000;
    /** A PDF holds fewer: past this it is thousands of pages no one reads. */
    static final int MAX_PDF_RECORDS = 2_000;
    /** Exports prepared at once on one instance; another asked meanwhile is told to try again (429). */
    static final int AT_ONCE = 2;

    private static final int PAGE = 500;

    private final DecisionRecordStore store;
    private final InventoryStore inventory;
    private final IdentityDirectory directory;
    private final ObjectMapper mapper;
    private final TransactionTemplate oneReading;
    private final Semaphore running = new Semaphore(AT_ONCE);
    /** Told each page's number as it is read: for a test to commit a decision mid-export. */
    private volatile IntConsumer pageRead = page -> { };

    public EvidenceExport(DecisionRecordStore store, InventoryStore inventory, IdentityDirectory directory,
                          ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.store = store;
        this.inventory = inventory;
        this.directory = directory;
        this.mapper = mapper;
        this.oneReading = new TransactionTemplate(transactions);
        this.oneReading.setReadOnly(true);
        this.oneReading.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** The two formats: CSV with every field, or a PDF to read that carries that CSV attached. */
    public enum Format {
        CSV("csv", "text/csv;charset=UTF-8"),
        PDF("pdf", "application/pdf");

        final String extension;
        final String contentType;

        Format(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String contentType() {
            return contentType;
        }

        static Format of(String format) {
            return switch (format.toLowerCase(Locale.ROOT)) {
                case "csv" -> CSV;
                case "pdf" -> PDF;
                default -> throw new ApiExceptions.BadRequest("format is csv or pdf");
            };
        }
    }

    /** What an export covers: the service (as named now, or by its records once removed), the period, the count. */
    public record Evidence(UUID serviceId, String serviceName, Instant from, Instant to, int records) {
    }

    /** Records read in order, one at a time; an export reads its pages through one. */
    @FunctionalInterface
    public interface Records {
        void forEach(Consumer<DecisionRecord> each);

        static Records of(List<DecisionRecord> records) {
            return records::forEach;
        }
    }

    /**
     * Who took each step, as the organization's directory names them now: a member by their display name, or as
     * "Member" and the start of their id if they have none (an export goes to auditors, so it carries no email
     * addresses); anyone no longer a member as "Former member" and the start of their id (only members act on an
     * organization's responses). The full id is in the CSV beside the name.
     */
    public record People(Map<UUID, String> members) {

        public People {
            members = Map.copyOf(members);
        }

        static People of(List<DirectoryUser> directory) {
            Map<UUID, String> members = new HashMap<>();
            for (DirectoryUser member : directory) {
                String name = member.displayName() == null ? "" : member.displayName().strip();
                members.put(member.id(), name);
            }
            return new People(members);
        }

        public String nameOf(UUID userId) {
            String name = members.get(userId);
            if (name == null) {
                return "Former member " + shortId(userId);
            }
            return name.isEmpty() ? "Member " + shortId(userId) : name;
        }

        private static String shortId(UUID userId) {
            return userId.toString().substring(0, 8);
        }
    }

    /** A finished export, on disk until closed: the controller sends it, then closes it, which deletes it. */
    public record ExportFile(Path path, String fileName, Format format, int records, String csvSha256)
            implements AutoCloseable {

        @Override
        public void close() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // A temporary file the system cleans up anyway.
            }
        }
    }

    /**
     * Export the service's evidence for {@code [from, to)}. Both ends are required. The service may since have been
     * removed: its records outlive it, named as they named it.
     *
     * @param exporter the user id of whoever exports (the token's subject), named on the PDF
     * @param zone     the time zone a PDF shows times in
     */
    public ExportFile export(UUID serviceId, Instant from, Instant to, String format, ZoneId zone, String exporter) {
        if (serviceId == null || from == null || to == null) {
            throw new ApiExceptions.BadRequest("An evidence export needs a service (serviceId) and a period (from, to)");
        }
        Format as = Format.of(format);
        DecisionTrailQuery query = new DecisionTrailQuery(serviceId, null, null, null, null, null, from, to);
        if (!running.tryAcquire()) {
            throw new ApiExceptions.TooManyRequests("Other evidence exports are being prepared; try again in a moment");
        }
        try {
            // Named before the reading, and all at once: a directory that can't be reached refuses the export.
            People people = People.of(directory.members());
            return oneReading.execute(status -> write(query, as, zone, people, generatedBy(exporter, people)));
        } finally {
            running.release();
        }
    }

    private ExportFile write(DecisionTrailQuery query, Format format, ZoneId zone, People people, String generatedBy) {
        Page<DecisionRecord> first = store.findPage(query, page(0));
        int limit = format == Format.PDF ? MAX_PDF_RECORDS : MAX_RECORDS;
        if (first.getTotalElements() > limit) {
            throw new ApiExceptions.BadRequest("This period holds " + first.getTotalElements() + " decision records, "
                    + "more than one " + format.extension.toUpperCase(Locale.ROOT) + " export takes (" + limit + ")"
                    + (format == Format.PDF ? ": export CSV, or a shorter period" : ": export a shorter period"));
        }
        Records records = each -> forEach(query, first, each);
        Path csv = temporary("csv");
        try {
            EvidenceCsv.Written written;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(csv))) {
                written = EvidenceCsv.write(records, people, out);
            }
            Evidence evidence = new Evidence(query.serviceId(), serviceName(query.serviceId(), written.lastServiceName()),
                    query.from(), query.to(), written.rows());
            String csvName = fileName(evidence, zone, Format.CSV);
            if (format == Format.CSV) {
                return new ExportFile(csv, csvName, format, written.rows(), written.sha256());
            }
            Path pdf = temporary("pdf");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(pdf))) {
                EvidencePdf.write(evidence, records, people, new EvidencePdf.Cover(generatedBy, Instant.now(), zone,
                        csv, csvName, written.sha256()), mapper, out);
            } catch (IOException | RuntimeException failed) {
                Files.deleteIfExists(pdf);
                throw failed;
            }
            Files.delete(csv);
            return new ExportFile(pdf, fileName(evidence, zone, Format.PDF), format, written.rows(), written.sha256());
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(csv);
            } catch (IOException ignored) {
                // A temporary file the system cleans up anyway.
            }
            if (failed instanceof IOException io) {
                throw new UncheckedIOException("The evidence export could not be written", io);
            }
            throw (RuntimeException) failed;
        }
    }

    /** Every record of the reading, page by page, the first page already read. */
    private void forEach(DecisionTrailQuery query, Page<DecisionRecord> first, Consumer<DecisionRecord> each) {
        Page<DecisionRecord> page = first;
        while (true) {
            pageRead.accept(page.getNumber());
            page.getContent().forEach(each);
            if (!page.hasNext()) {
                return;
            }
            page = store.findPage(query, page(page.getNumber() + 1));
        }
    }

    private static PageRequest page(int number) {
        return PageRequest.of(number, PAGE, Sort.by(Sort.Direction.ASC, "sequence"));
    }

    /** The service as the inventory names it now, or, once removed, as its latest record named it. */
    private String serviceName(UUID serviceId, String lastRecorded) {
        var service = inventory.findBusinessServicesByIds(List.of(serviceId)).get(serviceId);
        return service != null ? SensitiveText.redact(service.getName()) : lastRecorded;
    }

    private static String generatedBy(String exporter, People people) {
        try {
            return people.nameOf(UUID.fromString(exporter));
        } catch (IllegalArgumentException | NullPointerException notAUser) {
            return String.valueOf(exporter);
        }
    }

    private static Path temporary(String extension) {
        try {
            return Files.createTempFile("aegis-evidence-", "." + extension);
        } catch (IOException e) {
            throw new UncheckedIOException("No room to write the evidence export", e);
        }
    }

    /** e.g. {@code aegis-evidence-payments-api-2026-10-01-to-2026-10-31.pdf}: the service and the days covered. */
    static String fileName(Evidence evidence, ZoneId zone, Format format) {
        String slug = evidence.serviceName() == null ? "" : evidence.serviceName().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) {
            slug = "service";
        } else if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        LocalDate firstDay = evidence.from().atZone(zone).toLocalDate();
        LocalDate lastDay = evidence.to().minusNanos(1).atZone(zone).toLocalDate();
        return "aegis-evidence-" + slug + "-" + firstDay + "-to-" + lastDay + "." + format.extension;
    }

    /** For a test: be told each page's number as the export reads it. */
    void onPageRead(IntConsumer listener) {
        this.pageRead = listener == null ? page -> { } : listener;
    }

    /** For a test: the permits of the exports that may run at once. */
    Semaphore running() {
        return running;
    }
}
