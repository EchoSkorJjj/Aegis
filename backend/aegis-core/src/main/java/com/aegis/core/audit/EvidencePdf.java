package com.aegis.core.audit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.aegis.core.audit.DecisionRecord.Action;
import com.aegis.core.audit.DecisionRecord.Subject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * A service's evidence as a PDF (E10 story 3), for an auditor or regulator to read: what was exported and by whom,
 * then each decision record oldest first (when, what, who, why, the change and its impact then), then each record's
 * snapshot, the response as it stood. Times are shown in the reader's time zone; the CSV keeps them in UTC.
 *
 * <p>It names the SHA-256 of the CSV of the same records, so whoever holds both can check that they agree. The PDF uses
 * the standard fonts, which cover Latin-1 text only: any other character is printed as {@code ?} here and kept exactly
 * in the CSV.
 */
public final class EvidencePdf {

    private static final float MARGIN = 50;
    private static final float FOOTER = 30;
    private static final PDRectangle SIZE = PDRectangle.A4;
    private static final float WIDTH = SIZE.getWidth() - 2 * MARGIN;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /** Who made the export, when, in which time zone it is read, and the digest of the matching CSV. */
    public record Cover(String generatedBy, Instant generatedAt, ZoneId zone, String csvSha256) {
    }

    private EvidencePdf() {
    }

    public static byte[] write(EvidenceExport.Evidence evidence, Cover cover, ObjectMapper mapper) {
        try (PDDocument document = new PDDocument()) {
            Layout layout = new Layout(document);
            String service = evidence.serviceName() == null ? "A removed service" : evidence.serviceName();

            layout.text("Decision evidence", layout.bold, 18, 0);
            layout.gap(4);
            layout.text(service, layout.bold, 12, 0);
            layout.gap(8);
            layout.field("Service", service + " (" + evidence.serviceId() + ")");
            layout.field("Period", time(evidence.from(), cover.zone()) + " to " + time(evidence.to(), cover.zone())
                    + " (" + cover.zone().getId() + "; the end not included)");
            layout.field("Records", Integer.toString(evidence.records().size()));
            layout.field("Generated", time(cover.generatedAt(), cover.zone()) + " by " + cover.generatedBy());
            layout.field("CSV SHA-256", cover.csvSha256());
            layout.gap(8);
            layout.text("Every step taken in this period on a response covering this service, as Aegis recorded it "
                    + "when the step was taken: a risk ticket and its upgrade pull request for this service, and each "
                    + "regulator memo covering it. Records can't be changed or removed. The names, score and tier are "
                    + "as they were then; people are named as the organization's directory names them now. The CSV "
                    + "export of the same records has the SHA-256 above and keeps every field exactly.",
                    layout.regular, 9, 0);

            layout.gap(14);
            layout.text("Decisions", layout.bold, 14, 0);
            layout.gap(4);
            if (evidence.records().isEmpty()) {
                layout.text("No decision was recorded for this service in this period.", layout.regular, 10, 0);
            }
            for (DecisionRecord record : evidence.records()) {
                layout.keep(5 * 14);
                layout.text("#" + record.sequence() + "  " + time(record.occurredAt(), cover.zone()) + "  "
                        + label(record.action()) + " - " + label(record.subject()), layout.bold, 10, 0);
                layout.text("By " + evidence.nameOf(record.actorId()) + impact(record), layout.regular, 9, 12);
                layout.text("Change: " + record.changeTitle(), layout.regular, 9, 12);
                if (record.serviceIds().size() > 1) {
                    layout.text("Covers " + record.serviceIds().size() + " services; most affected: "
                            + record.serviceName(), layout.regular, 9, 12);
                }
                if (record.reason() != null) {
                    layout.text("Why: " + record.reason(), layout.regular, 9, 12);
                }
                layout.text("Response " + record.responseId() + " - record " + record.id(), layout.regular, 7, 12);
                layout.gap(6);
            }

            if (!evidence.records().isEmpty()) {
                layout.page();
                layout.text("The responses as they stood", layout.bold, 14, 0);
                layout.text("Each record's snapshot, as recorded with it.", layout.regular, 9, 0);
                layout.gap(6);
                for (DecisionRecord record : evidence.records()) {
                    layout.keep(3 * 12);
                    layout.text("#" + record.sequence() + "  " + label(record.action()) + " - "
                            + label(record.subject()), layout.bold, 9, 0);
                    layout.mono(pretty(record.state(), mapper), 6.5f, 12);
                    layout.gap(6);
                }
            }

            layout.finish("Aegis decision evidence - " + service);
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("Decision evidence: " + printable(service, layout.regular));
            info.setCreator("Aegis");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("The evidence PDF could not be written", e);
        }
    }

    static String time(Instant instant, ZoneId zone) {
        return TIME.format(instant.atZone(zone));
    }

    static String label(Action action) {
        return switch (action) {
            case DRAFTED -> "Drafted";
            case EDITED -> "Edited";
            case OVERRIDDEN -> "Overridden";
            case OVERRIDE_WITHDRAWN -> "Override withdrawn";
            case APPROVED -> "Approved";
            case FILED -> "Filed";
            case FILING_FAILED -> "Filing failed";
            case OPENED -> "Pull request opened";
            case OPENING_FAILED -> "Pull request opening failed";
            case DISMISSED -> "Dismissed";
        };
    }

    static String label(Subject subject) {
        return switch (subject) {
            case RISK_TICKET -> "Risk ticket";
            case UPGRADE_PULL_REQUEST -> "Upgrade pull request";
            case REGULATOR_MEMO -> "Regulator memo";
        };
    }

    private static String impact(DecisionRecord record) {
        if (record.score() == null || record.tier() == null) {
            return "";
        }
        return String.format(Locale.ROOT, " - impact then %.1f (%s)", record.score(), record.tier().name());
    }

    private static String pretty(String state, ObjectMapper mapper) {
        if (state == null) {
            return "(no snapshot)";
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(state));
        } catch (JacksonException notJson) {
            return state;
        }
    }

    /**
     * {@code text} as the standard fonts can print it: control characters as spaces (a tab as four), any character
     * outside the font's encoding as {@code ?}.
     */
    static String printable(String text, PDFont font) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp >= 0x20 && cp < 0x7F) {
                out.append((char) cp);
            } else if (cp == '\t') {
                out.append("    ");
            } else if (Character.isISOControl(cp)) {
                out.append(' ');
            } else {
                String character = new String(Character.toChars(cp));
                try {
                    font.encode(character);
                    out.append(character);
                } catch (IllegalArgumentException | IOException notInTheFont) {
                    out.append('?');
                }
            }
        });
        return out.toString();
    }

    /** Lines of text down A4 pages, starting a page when one fills. */
    private static final class Layout {

        final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        final PDFont mono = new PDType1Font(Standard14Fonts.FontName.COURIER);

        private final PDDocument document;
        private PDPageContentStream stream;
        private float y;

        Layout(PDDocument document) throws IOException {
            this.document = document;
            page();
        }

        void page() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(SIZE);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = SIZE.getHeight() - MARGIN;
        }

        /** Start a new page unless {@code height} more fits on this one: keeps a record's first lines together. */
        void keep(float height) throws IOException {
            if (y - height < MARGIN + FOOTER) {
                page();
            }
        }

        void gap(float height) {
            y -= height;
        }

        void field(String name, String value) throws IOException {
            text(name + ": " + value, regular, 10, 0);
        }

        /** Wrapped at words, a word too long for a line broken where it must be; each line break kept. */
        void text(String text, PDFont font, float size, float indent) throws IOException {
            float width = WIDTH - indent;
            for (String paragraph : lines(text, font)) {
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.split(" ")) {
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (width(candidate, font, size) <= width) {
                        line.setLength(0);
                        line.append(candidate);
                        continue;
                    }
                    if (!line.isEmpty()) {
                        line(line.toString(), font, size, indent);
                        line.setLength(0);
                    }
                    String rest = word;
                    while (width(rest, font, size) > width) {
                        int fits = fits(rest, font, size, width);
                        line(rest.substring(0, fits), font, size, indent);
                        rest = rest.substring(fits);
                    }
                    line.append(rest);
                }
                line(line.toString(), font, size, indent);
            }
        }

        /** Fixed-width text (a snapshot's JSON), its indentation kept, long lines broken at the margin. */
        void mono(String text, float size, float indent) throws IOException {
            int perLine = (int) ((WIDTH - indent) / (0.6f * size));
            for (String raw : lines(text, mono)) {
                for (int start = 0; start == 0 || start < raw.length(); start += perLine) {
                    line(raw.substring(start, Math.min(raw.length(), start + perLine)), mono, size, indent);
                }
            }
        }

        /** The text's lines, each made printable: split first, or the line breaks would be printed as spaces. */
        private static String[] lines(String text, PDFont font) {
            String[] lines = text.split("\r\n|\r|\n", -1);
            for (int i = 0; i < lines.length; i++) {
                lines[i] = printable(lines[i], font);
            }
            return lines;
        }

        /** The footer on every page: what the document is, and the page out of how many. */
        void finish(String title) throws IOException {
            stream.close();
            int count = document.getNumberOfPages();
            String safeTitle = printable(title, regular);
            for (int i = 0; i < count; i++) {
                try (PDPageContentStream footer = new PDPageContentStream(document, document.getPage(i),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    footer.beginText();
                    footer.setFont(regular, 7);
                    footer.newLineAtOffset(MARGIN, MARGIN - 20);
                    footer.showText(safeTitle + " - page " + (i + 1) + " of " + count);
                    footer.endText();
                }
            }
        }

        private void line(String text, PDFont font, float size, float indent) throws IOException {
            float leading = size * 1.35f;
            if (y - leading < MARGIN + FOOTER) {
                page();
            }
            y -= leading;
            if (!text.isEmpty()) {
                stream.beginText();
                stream.setFont(font, size);
                stream.newLineAtOffset(MARGIN + indent, y);
                stream.showText(text);
                stream.endText();
            }
        }

        private static float width(String text, PDFont font, float size) throws IOException {
            return font.getStringWidth(text) / 1000 * size;
        }

        /** How many leading characters of {@code text} fit in {@code width}: at least one. */
        private static int fits(String text, PDFont font, float size, float width) throws IOException {
            int count = 1;
            while (count < text.length() && width(text.substring(0, count + 1), font, size) <= width) {
                count++;
            }
            return count;
        }
    }
}
