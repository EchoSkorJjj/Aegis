package com.aegis.core.actions.assess;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.actions.assess.ReachabilityAssessment.Cited;
import com.aegis.core.actions.assess.ReachabilityAssessment.Verdict;
import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.ai.AiAssessor;
import com.aegis.core.ai.AiDtos;
import com.aegis.core.inventory.CodeUsage;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.Reachability;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ImportScan;
import com.aegis.core.inventory.sbom.RepositoryCode;
import com.aegis.core.matching.MatchingDtos.ChangeMatchResponse;
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.matching.MatchingDtos.EvidenceChain;
import com.aegis.core.matching.MatchingDtos.EvidenceKind;
import com.aegis.core.matching.MatchingDtos.ImpactedServiceMatch;
import com.aegis.core.matching.MatchingService;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.common.SensitiveText;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The AI's suggestion on whether a service's code uses the vulnerable function, feature or condition of a package it
 * imports (E11-5). An item — a vulnerability in a service — is assessed only for a package the code imports itself
 * ({@link Reachability}: {@code IMPORTED}, not through another package), only when someone asks or a ticket is drafted
 * for it, never on a page load. Two passes: the AI names what to search the code for, from the advisory and what its fix
 * commit changed ({@link FixCommits}); Aegis checks the code out at the commit the import read ({@link RepositoryCode};
 * Aegis never stores code) and takes where it uses what it imports and where it names those terms; the AI answers from
 * up to {@value #MAX_EXCERPTS} such excerpts and the search's findings. Only the advisory, the fix summary and those
 * lines leave Aegis, redacted ({@link AiDtos}). The answer is kept with the line ranges it cites, its checks and what was
 * searched; it changes no score and decides nothing. Anything it can't answer says why.
 *
 * <p>This is an AI suggestion from targeted excerpts, not call-graph reachability: it reads a few lines, not every path
 * from the service's entry points to the vulnerable function.
 */
@Service
public class ReachabilityAssessments {

    private static final Logger log = LoggerFactory.getLogger(ReachabilityAssessments.class);

    static final int MAX_EXCERPTS = 6;
    static final int MAX_ADVISORY_CHARS = 2500;
    static final int MAX_RATIONALE_CHARS = 4000;
    static final int MAX_CHECKS = 3;
    static final int MAX_CHECK_CHARS = 200;
    static final String NOT_ASKED = "Nobody has asked the AI yet";

    /** Whether the AI has answered for this package. */
    public enum Status { ASSESSED, NOT_ASSESSED }

    /** Lines {@code startLine}–{@code endLine} of {@code path} the answer cites, linked at the commit read. */
    public record CitedLines(String path, int startLine, int endLine, int line, String url) {
    }

    /**
     * One package of an item: the AI's answer — {@code verdict}, {@code rationale}, the lines it cites, what a reviewer
     * should check, what the code was searched for, when — or, not assessed, why ({@code reason}); {@code assessable}
     * says whether asking can answer it.
     */
    public record Item(UUID serviceId, String ecosystem, String packageName, Status status, Verdict verdict,
                       String rationale, List<CitedLines> citedLines, List<String> checks,
                       List<ReachabilityAssessment.Searched> searches, Instant assessedAt, String reason,
                       boolean assessable) {

        public Item {
            citedLines = citedLines == null ? List.of() : List.copyOf(citedLines);
            checks = checks == null ? List.of() : List.copyOf(checks);
            searches = searches == null ? List.of() : List.copyOf(searches);
        }
    }

    private final MatchingService matching;
    private final Reachability reachability;
    private final InventoryService inventoryService;
    private final InventoryStore inventory;
    private final RepositoryCode code;
    private final AiAssessor ai;
    private final ChangeSignalStore signals;
    private final AdvisoryStore advisories;
    private final ReachabilityAssessmentStore store;
    private final FixCommits fixCommits;

    ReachabilityAssessments(MatchingService matching, Reachability reachability,
            InventoryService inventoryService, InventoryStore inventory, RepositoryCode code, AiAssessor ai,
            ChangeSignalStore signals, AdvisoryStore advisories, ReachabilityAssessmentStore store,
            FixCommits fixCommits) {
        this.matching = matching;
        this.reachability = reachability;
        this.inventoryService = inventoryService;
        this.inventory = inventory;
        this.code = code;
        this.ai = ai;
        this.signals = signals;
        this.advisories = advisories;
        this.store = store;
        this.fixCommits = fixCommits;
    }

    /** Every impacted service's packages for this change, each with the AI's answer or why it hasn't one. */
    public List<Item> list(UUID changeSignalId) {
        Context context = context(changeSignalId, null);
        List<Item> items = new ArrayList<>();
        for (Target target : context.targets()) {
            items.add(kept(context, target).map(saved -> assessed(saved, target.service()))
                    .orElseGet(() -> target.assessable() ? notAssessed(target, NOT_ASKED, true)
                            : notAssessed(target, target.verdict().reason(), false)));
        }
        return items;
    }

    /** Ask the AI now about every package of this change the service's code imports itself. */
    public List<Item> assess(UUID serviceId, UUID changeSignalId, UUID requestedBy) {
        inventoryService.getBusinessService(serviceId); // 404 outside the caller's organization, before any work
        Context context = context(changeSignalId, serviceId);
        return context.targets().stream().map(target -> target.assessable() ? run(context, target, requestedBy)
                : notAssessed(target, target.verdict().reason(), false)).toList();
    }

    /**
     * What a drafted ticket says about each package: the answer kept for it, else one asked for now — never failing
     * the draft: anything that goes wrong is "not assessed", with why.
     */
    public List<Item> forDraft(UUID serviceId, UUID changeSignalId, UUID requestedBy) {
        try {
            Context context = context(changeSignalId, serviceId);
            return context.targets().stream().map(target -> kept(context, target)
                    .map(saved -> assessed(saved, target.service()))
                    .orElseGet(() -> target.assessable() ? run(context, target, requestedBy)
                            : notAssessed(target, target.verdict().reason(), false))).toList();
        } catch (RuntimeException failure) {
            log.warn("reachability assessment for a draft in service {}: not assessed ({})", serviceId,
                    SensitiveText.redact(failure.toString()));
            return List.of();
        }
    }

    // --- the work ----------------------------------------------------------------------------------

    /** A package of the change in one service, with whether the code reaches it. */
    private record Target(BusinessService service, String ecosystem, String packageName, Reachability.Verdict verdict) {

        boolean assessable() {
            return verdict.status() == CodeUsage.Status.IMPORTED && verdict.through() == null && verdict.usage() != null
                    && !verdict.usage().sites().isEmpty() && service.getImportScanRevision() != null;
        }
    }

    /** The change's vulnerability, its advisory text and fix commits, and its packages in each impacted service. */
    private record Context(String vulnerabilityId, String advisoryText, List<String> fixCommits, List<Target> targets,
                           Map<UUID, List<ReachabilityAssessment>> kept) {
    }

    private Context context(UUID changeSignalId, UUID onlyService) {
        ChangeMatchResponse match = matching.matchChange(changeSignalId); // 404 / 400 as matching answers
        if (match.change().signalType() != ChangeSignal.SignalType.VULNERABILITY) {
            return new Context(null, null, List.of(), List.of(), Map.of());
        }
        // The vulnerable components matching found in each service, by package.
        Map<UUID, Map<String, Set<UUID>>> packages = new LinkedHashMap<>();
        Map<String, ComponentRef> named = new LinkedHashMap<>();
        for (ImpactedServiceMatch impacted : match.impactedServices()) {
            if (onlyService != null && !onlyService.equals(impacted.service().id())) {
                continue;
            }
            for (EvidenceChain chain : impacted.evidence()) {
                if (chain.kind() == EvidenceKind.ADVISORY_COMPONENT && !chain.componentPath().isEmpty()) {
                    ComponentRef component = chain.componentPath().getFirst();
                    String key = component.ecosystem() + "\0" + component.name();
                    named.putIfAbsent(key, component);
                    packages.computeIfAbsent(impacted.service().id(), id -> new LinkedHashMap<>())
                            .computeIfAbsent(key, k -> new LinkedHashSet<>()).add(component.id());
                }
            }
        }
        if (packages.isEmpty()) {
            return new Context(null, null, List.of(), List.of(), Map.of());
        }
        Map<UUID, Set<UUID>> components = new LinkedHashMap<>();
        packages.forEach((serviceId, byPackage) -> byPackage.values()
                .forEach(ids -> components.computeIfAbsent(serviceId, id -> new LinkedHashSet<>()).addAll(ids)));
        Reachability.Reached reached = reachability.of(components);
        Map<UUID, BusinessService> services = inventory.findBusinessServicesByIds(packages.keySet());
        List<Target> targets = new ArrayList<>();
        packages.forEach((serviceId, byPackage) -> {
            BusinessService service = services.get(serviceId);
            if (service != null) {
                byPackage.forEach((key, ids) -> targets.add(new Target(service, named.get(key).ecosystem(),
                        named.get(key).name(), reached.strongest(serviceId, ids))));
            }
        });

        ChangeSignal signal = signals.findById(changeSignalId).orElse(null);
        Advisory advisory = signal == null ? null
                : advisories.findByExternalId(signal.sourceId(), signal.externalId()).orElse(null);
        String vulnerabilityId = advisory != null && advisory.canonicalId() != null ? advisory.canonicalId()
                : match.change().externalId();
        String advisoryText = advisoryText(match.change().title(), advisory == null ? null : advisory.description(),
                advisory == null ? null : advisory.details(), signal == null ? null : signal.summary());
        Map<UUID, List<ReachabilityAssessment>> kept = new LinkedHashMap<>();
        store.findFor(services.keySet(), vulnerabilityId).forEach(saved ->
                kept.computeIfAbsent(saved.serviceId(), id -> new ArrayList<>()).add(saved));
        return new Context(vulnerabilityId, advisoryText, advisory == null ? List.of() : advisory.fixCommits(), targets,
                kept);
    }

    /** The answer kept for this package at the commit the service's code was last read at. */
    private static Optional<ReachabilityAssessment> kept(Context context, Target target) {
        return context.kept().getOrDefault(target.service().getId(), List.of()).stream()
                .filter(saved -> saved.ecosystem().equals(target.ecosystem())
                        && saved.packageName().equals(target.packageName())
                        && saved.revision().equals(target.service().getImportScanRevision()))
                .findFirst();
    }

    private Item run(Context context, Target target, UUID requestedBy) {
        BusinessService service = target.service();
        RepositoryCode.Opened opened = code.open(service, target.ecosystem());
        if (opened.unreadable() != null) {
            return notAssessed(target, opened.unreadable(), true);
        }
        try (RepositoryCode.Code checkout = opened.code()) {
            AiDtos.PackageRef pkg = new AiDtos.PackageRef(target.ecosystem(), target.packageName());
            String fix = fixCommits.summary(context.fixCommits());
            // Pass 1: what to search the code for. An answer that can't be used just means no search.
            List<String> terms;
            try {
                terms = ai.lookFor(new AiDtos.LookForRequest(UUID.randomUUID().toString(), context.advisoryText(), pkg,
                        fix)).terms();
            } catch (RuntimeException failure) {
                if (!unusable(failure)) {
                    return notAssessed(target, unavailable(failure), true);
                }
                terms = List.of();
            }
            RepositoryCode.Read read = checkout.read(target.verdict().usage().sites().stream()
                    .map(site -> new ImportScan.Site(site.path(), site.line())).toList(), terms, MAX_EXCERPTS);
            if (!read.importsFound() || read.excerpts().isEmpty()) {
                return notAssessed(target, "The importing lines aren't in the repository at commit "
                        + service.getImportScanRevision().substring(0, 7) + " any more; import the SBOM from it again",
                        true);
            }
            List<RepositoryCode.Excerpt> excerpts = read.excerpts();
            List<AiDtos.CodeExcerpt> sent = new ArrayList<>();
            for (int index = 0; index < excerpts.size(); index++) {
                RepositoryCode.Excerpt excerpt = excerpts.get(index);
                sent.add(new AiDtos.CodeExcerpt("E" + (index + 1), excerpt.path(), excerpt.startLine(), excerpt.text(),
                        excerpt.kind().name().toLowerCase(Locale.ROOT), excerpt.term()));
            }
            List<AiDtos.Search> searches = read.searches().stream().map(search -> new AiDtos.Search(search.term(),
                    search.filesSearched(), search.filesMatched(), search.linesMatched(), search.complete())).toList();

            // Pass 2: the answer.
            AiDtos.Assessment answer;
            try {
                answer = ai.assess(new AiDtos.AssessmentRequest(UUID.randomUUID().toString(), context.advisoryText(),
                        pkg, fix, searches, sent));
            } catch (RuntimeException failure) {
                return notAssessed(target, unavailable(failure), true);
            }
            Verdict verdict;
            try {
                verdict = Verdict.valueOf(answer.verdict());
            } catch (RuntimeException unknown) {
                return notAssessed(target, "The AI's answer couldn't be used; try again", true);
            }
            List<Cited> cited = new ArrayList<>();
            for (String id : new LinkedHashSet<>(answer.citedExcerpts())) {
                int index = id != null && id.matches("E[1-9]") ? Integer.parseInt(id.substring(1)) - 1 : -1;
                if (index >= 0 && index < excerpts.size()) {
                    RepositoryCode.Excerpt excerpt = excerpts.get(index);
                    int lineCount = Math.max(excerpt.text().split("\n", -1).length, 1);
                    cited.add(new Cited(excerpt.path(), excerpt.startLine(), excerpt.startLine() + lineCount - 1,
                            excerpt.line()));
                }
            }
            String rationale = cut(SensitiveText.redact(answer.rationale() == null ? "" : answer.rationale().strip()),
                    MAX_RATIONALE_CHARS);
            List<String> checks = answer.checks().stream().filter(check -> check != null && !check.isBlank())
                    .map(check -> cut(SensitiveText.redact(check.strip()), MAX_CHECK_CHARS)).limit(MAX_CHECKS).toList();
            List<ReachabilityAssessment.Searched> searched = read.searches().stream()
                    .map(search -> new ReachabilityAssessment.Searched(search.term(), search.linesMatched(),
                            search.filesMatched())).toList();
            ReachabilityAssessment saved = store.save(new ReachabilityAssessment(UUID.randomUUID(), service.getId(),
                    target.ecosystem(), target.packageName(), context.vulnerabilityId(),
                    service.getImportScanRevision(), verdict, rationale, cited, checks, searched, requestedBy,
                    Instant.now()));
            return assessed(saved, service);
        }
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static Item assessed(ReachabilityAssessment saved, BusinessService service) {
        String ref = saved.revision();
        List<CitedLines> cited = saved.cited().stream().map(lines -> new CitedLines(lines.path(), lines.startLine(),
                lines.endLine(), lines.line(), CodeUsage.lineUrl(service.getRepositoryUrl(), ref, lines.path(),
                lines.line()))).toList();
        return new Item(saved.serviceId(), saved.ecosystem(), saved.packageName(), Status.ASSESSED, saved.verdict(),
                saved.rationale(), cited, saved.checks(), saved.searches(), saved.assessedAt(), null, true);
    }

    private static Item notAssessed(Target target, String reason, boolean assessable) {
        return new Item(target.service().getId(), target.ecosystem(), target.packageName(), Status.NOT_ASSESSED, null,
                null, List.of(), List.of(), List.of(), null,
                reason == null ? "Aegis can't tell whether the code imports it" : reason, assessable);
    }

    /** Whether the AI answered but its answer couldn't be used (the ai-service refused it: 502). */
    private static boolean unusable(RuntimeException failure) {
        return failure instanceof ApiExceptions.UpstreamUnavailable && failure.getMessage() != null
                && failure.getMessage().contains("(HTTP 502)");
    }

    /** Why the AI gave no answer, from the port's safe failure. */
    private static String unavailable(RuntimeException failure) {
        String message = failure instanceof ApiExceptions.UpstreamUnavailable ? failure.getMessage() : null;
        if (message != null && message.contains("(HTTP 400)")) {
            return "The AI service refused the request: the advisory or the code holds text it won't take as data";
        }
        if (message != null && message.contains("(HTTP 429)")) {
            return "The AI is at its per-minute limit, which every assessment and draft shares; ask again in a minute";
        }
        if (message != null && message.contains("(HTTP 502)")) {
            return "The AI's answer cited lines it wasn't given, so it was refused; try again";
        }
        return "The AI service is unavailable; try again later";
    }

    /**
     * The change's title, its advisory's description (else the signal's summary) and the advisory's details (V42 — what
     * OSV says beyond its one-line summary), redacted and cut to {@value #MAX_ADVISORY_CHARS} characters.
     */
    static String advisoryText(String title, String description, String details, String summary) {
        String body = description != null && !description.isBlank() ? description : summary;
        StringBuilder text = new StringBuilder(title == null ? "" : title.strip());
        for (String part : new String[] {body, details}) {
            if (part != null && !part.isBlank() && !text.toString().contains(part.strip())) {
                text.append(text.isEmpty() ? "" : "\n\n").append(part.strip());
            }
        }
        return cut(SensitiveText.redact(text.toString()), MAX_ADVISORY_CHARS);
    }
}
