package com.aegis.core.actions.memo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.aegis.core.actions.memo.RegulatorMemo.AffectedService;
import com.aegis.core.inventory.domain.BusinessCriticality;

/**
 * Renders a DORA notification from its template ({@code classpath:memo/}): Aegis's facts and the drafted
 * sections into {@code {{placeholders}}}, in one pass, so nothing a feed or the AI wrote is ever read as a
 * placeholder. What Aegis cannot know is left in the template as "[To be confirmed]".
 */
final class MemoTemplate {

    static final String TO_BE_CONFIRMED = "[To be confirmed]";
    /** DORA: the initial notification is due no later than 24 hours after the entity became aware of the incident. */
    static final Duration INITIAL_NOTIFICATION = Duration.ofHours(24);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z_]+)}}");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Map<MemoKind, String> TEMPLATES = new EnumMap<>(MemoKind.class);

    static {
        for (MemoKind kind : MemoKind.values()) {
            try (InputStream in = MemoTemplate.class.getResourceAsStream("/memo/" + kind.template())) {
                if (in == null) {
                    throw new IllegalStateException("Missing memo template " + kind.template());
                }
                TEMPLATES.put(kind, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }

    private MemoTemplate() {
    }

    /** What the memo says about the change, beyond the drafted sections. */
    record Facts(String reference, String change, String severity, Instant detectedAt, List<AffectedService> services,
                 List<String> thirdParties, List<String> components, List<String> evidence) {
    }

    /** The drafted sections: the AI's, or Aegis's plain fallback. */
    record Sections(String summary, String impact, String measures, String nextSteps) {
    }

    static String render(MemoKind kind, Facts facts, Sections sections) {
        Map<String, String> values = Map.ofEntries(
                Map.entry("reference", facts.reference()),
                Map.entry("change", facts.change()),
                Map.entry("severity", facts.severity() == null ? TO_BE_CONFIRMED : facts.severity()),
                Map.entry("detected", facts.detectedAt() == null ? TO_BE_CONFIRMED : WHEN.format(facts.detectedAt())),
                Map.entry("due", facts.detectedAt() == null
                        ? "no later than 24 hours after detection, and within 4 hours of classifying it as major"
                        : "no later than " + WHEN.format(facts.detectedAt().plus(INITIAL_NOTIFICATION))
                                + " (24 hours after detection), and within 4 hours of classifying it as major"),
                Map.entry("services", table(facts.services())),
                Map.entry("critical_functions", criticalFunctions(facts.services())),
                Map.entry("third_party", facts.thirdParties().isEmpty() ? "None identified by Aegis. " + TO_BE_CONFIRMED
                        : bullets(facts.thirdParties())),
                Map.entry("components", facts.components().isEmpty() ? TO_BE_CONFIRMED : bullets(facts.components())),
                Map.entry("evidence", bullets(facts.evidence())),
                Map.entry("summary", sections.summary()),
                Map.entry("impact", sections.impact()),
                Map.entry("measures", sections.measures()),
                Map.entry("next_steps", sections.nextSteps()));
        Matcher placeholder = PLACEHOLDER.matcher(TEMPLATES.get(kind));
        StringBuilder memo = new StringBuilder();
        while (placeholder.find()) {
            String value = values.get(placeholder.group(1));
            if (value == null) {
                throw new IllegalStateException("Unknown memo placeholder " + placeholder.group(1));
            }
            placeholder.appendReplacement(memo, Matcher.quoteReplacement(value.strip()));
        }
        placeholder.appendTail(memo);
        return memo.toString();
    }

    /** Critical or high-criticality services Aegis found materially affected, or say it found none. */
    static String criticalFunctions(List<AffectedService> services) {
        List<String> critical = services.stream()
                .filter(service -> service.material() && (service.criticality() == BusinessCriticality.CRITICAL
                        || service.criticality() == BusinessCriticality.HIGH))
                .map(service -> service.name() + " (" + service.criticality() + ")")
                .toList();
        return critical.isEmpty() ? "None identified by Aegis. " + TO_BE_CONFIRMED : "Yes: " + String.join(", ", critical);
    }

    private static String table(List<AffectedService> services) {
        String rows = services.stream()
                .map(service -> "| " + cell(service.name()) + " | " + service.criticality() + " | "
                        + (service.exposure() == null ? "unknown" : service.exposure()) + " | "
                        + String.format(Locale.ROOT, "%.1f", service.score()) + " (" + service.tier() + ") | "
                        + (service.material() ? "yes" : "no") + " |")
                .collect(Collectors.joining("\n"));
        return "| Service | Criticality | Exposure | Impact score | Material |\n|---|---|---|---|---|\n" + rows;
    }

    private static String bullets(List<String> items) {
        return items.stream().map(item -> "- " + item.strip().replaceAll("\\s+", " ")).collect(Collectors.joining("\n"));
    }

    /** A table cell: a pipe or line break in a name must not break the row. */
    private static String cell(String text) {
        return text.replace("|", "/").replaceAll("\\s+", " ");
    }
}
