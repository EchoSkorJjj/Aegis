package com.aegis.core.inventory.sbom;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ImportScan;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.platform.common.ApiExceptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A service's code, read back on demand at the commit its last import read (E11-5), for the AI to judge whether the code
 * uses what an advisory says is vulnerable. Aegis never stores code: {@link #open} checks out only the package language's
 * source files, through the import's own git and access (the organization's GitHub App, else anonymously — a token pasted
 * for an import is never kept), and closing the {@link Code} deletes them. From it, {@link Code#read} takes a few lines
 * where the code uses what it imports ({@link ImportBindings}) and where it names what the advisory or its fix names.
 */
@Component
public class RepositoryCode {

    /** Lines shown either side of a use or a match. */
    public static final int AROUND = 3;
    /** The longest excerpt, in characters. */
    public static final int MAX_EXCERPT_CHARS = 600;
    /** The most places shown for one searched name. */
    static final int MATCHES_PER_TERM = 3;
    private static final int MAX_TERMS = 5;
    private static final int MATCHES_KEPT = 20;
    private static final Pattern TERM = Pattern.compile("[A-Za-z_$@][A-Za-z0-9_$.:/@-]{3,63}");
    /** A qualified type ({@code java.nio.file.Path}): its package's wildcard import declares it too. */
    private static final Pattern QUALIFIED_TYPE = Pattern.compile("((?:[a-z_][\\w]*\\.)+)[A-Z][\\w]*");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)password|passwd|secret|credential|token|api[_-]?key|private[_-]?key|access[_-]?key");

    /** Why lines were shown: where the code imports the package, uses what it imports, or names a searched term. */
    public enum Kind { IMPORT, USE, MATCH }

    /**
     * Lines {@code startLine} on of {@code path}, around {@code line} — the use, match or import they were taken for;
     * {@code term} is the searched name a {@link Kind#MATCH} found.
     */
    public record Excerpt(String path, int startLine, int line, String text, Kind kind, String term) {
    }

    /** What the search for one name found: in how many of the files read, and whether every file was read. */
    public record Searched(String term, int filesSearched, int filesMatched, int linesMatched, boolean complete) {
    }

    /** The excerpts, each name's search, and whether any file importing the package is still there. */
    public record Read(List<Excerpt> excerpts, List<Searched> searches, boolean importsFound) {

        public Read {
            excerpts = List.copyOf(excerpts);
            searches = List.copyOf(searches);
        }
    }

    /** The service's code, checked out — or, when it can't be read, why ({@code unreadable}). */
    public record Opened(Code code, String unreadable) {

        static Opened unreadable(String why) {
            return new Opened(null, why);
        }
    }

    private final SbomGenerationProperties properties;
    private final RepoSbomGenerator generator;
    private final RepositoryAccess access;

    @Autowired
    RepositoryCode(SbomGenerationProperties properties, ObjectProvider<RepoSbomGenerator> generator,
            RepositoryAccess access) {
        this(properties, generator.getIfAvailable(), access);
    }

    RepositoryCode(SbomGenerationProperties properties, RepoSbomGenerator generator, RepositoryAccess access) {
        this.properties = properties;
        this.generator = generator;
        this.access = access;
    }

    /** The service's {@code ecosystem} source files at the commit its last import read. The caller closes it. */
    public Opened open(BusinessService service, String ecosystem) {
        String revision = service.getImportScanRevision();
        if (service.getImportScannedAt() == null || service.getRepositoryUrl() == null || revision == null
                || !revision.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            return Opened.unreadable("The service's code hasn't been read: import the SBOM from its repository");
        }
        if (generator == null) {
            return Opened.unreadable("Reading the service's code is switched off on this server");
        }
        String language = language(ecosystem);
        if (language == null) {
            return Opened.unreadable("Aegis doesn't read " + ecosystem + " code yet");
        }
        RepositoryUrl repository;
        try {
            repository = RepositoryUrl.parse(service.getRepositoryUrl(), properties.allowedHosts());
        } catch (RuntimeException notAllowed) {
            return Opened.unreadable("Aegis no longer reads repositories on that host");
        }
        CloneAccess clone = access.access(repository, null, null);
        try {
            RepoSbomGenerator.Checkout checkout = generator.checkout(repository, service.getRepositoryBranch(), revision,
                    patterns(language), clone);
            return new Opened(new Code(checkout, language), null);
        } catch (RepoSbomGenerator.CommitGone gone) {
            return Opened.unreadable("The commit the import read (" + revision.substring(0, 7) + ") is no longer in "
                    + repository.canonical() + "; import the SBOM from it again");
        } catch (ApiExceptions.BadRequest unreadable) {
            String why = unreadable.getMessage();
            return Opened.unreadable("Aegis can't read " + repository.canonical() + " now: "
                    + (clone.source() == CloneAccess.Source.ANONYMOUS && why != null && why.contains("is private")
                    ? "it may be private, and an access token pasted for an import isn't kept — connect the GitHub App"
                            + " to read it"
                    : lowercaseFirst(why)));
        }
    }

    /** The scan's language for a package's ecosystem, or null for one Aegis doesn't read. */
    static String language(String ecosystem) {
        return switch (ecosystem == null ? "" : ecosystem.strip().toLowerCase(Locale.ROOT)) {
            case "npm" -> "npm";
            case "pypi", "pip" -> "pypi";
            case "maven" -> "maven";
            default -> null;
        };
    }

    /** The sparse checkout for a language: its source files in any case, vendored and build folders left out. */
    static List<String> patterns(String language) {
        Stream<String> sources = ImportScanner.LANGUAGE_BY_EXTENSION.entrySet().stream()
                .filter(entry -> entry.getValue().equals(language)).map(Map.Entry::getKey).sorted()
                .map(extension -> "*." + RepoSbomGenerator.anyCase(extension));
        Stream<String> skipped = ImportScanner.SKIPPED_DIRECTORIES.stream().filter(dir -> !dir.equals(".git")).sorted()
                .map(dir -> "!" + dir + "/");
        return Stream.concat(sources, skipped).toList();
    }

    private static String lowercaseFirst(String text) {
        return text == null || text.isEmpty() ? "it couldn't be read"
                : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * The checked-out source files of one language, read with the import scan's rules (no vendored or build folders,
     * minified files, links or secret-looking names; {@link ImportScanner#MAX_FILE_BYTES} a file,
     * {@link ImportScanner#MAX_FILES} files, {@link ImportScanner#MAX_TOTAL_BYTES} in all). Closing it deletes them.
     */
    public static final class Code implements AutoCloseable {

        private final RepoSbomGenerator.Checkout checkout;
        private final String language;
        private final List<Path> files = new ArrayList<>();
        private final Set<Path> readable = new HashSet<>();
        private final Map<String, String> texts = new HashMap<>();
        private boolean complete = true;

        Code(RepoSbomGenerator.Checkout checkout, String language) {
            this.checkout = checkout;
            this.language = language;
            walk();
        }

        /**
         * Up to {@code maxExcerpts} excerpts: first where the code uses what it imports at {@code sites} (one file each,
         * for half of them; its import where it uses nothing), then where it names each of {@code terms} — in files not
         * yet shown first, at most {@value #MATCHES_PER_TERM} a name — then more uses. A name that isn't a plain name of
         * at least four characters, or that names a secret, is never searched.
         */
        public Read read(List<ImportScan.Site> sites, List<String> terms, int maxExcerpts) {
            Map<String, List<Integer>> byFile = new LinkedHashMap<>();
            sites.forEach(site -> byFile.computeIfAbsent(site.path(), path -> new ArrayList<>()).add(site.line()));
            List<List<Excerpt>> uses = new ArrayList<>();
            for (Map.Entry<String, List<Integer>> file : byFile.entrySet()) {
                String text = text(file.getKey());
                if (text != null) {
                    uses.add(uses(file.getKey(), text, file.getValue(), Kind.IMPORT, null));
                }
            }
            List<Excerpt> excerpts = new ArrayList<>();
            for (int index = 0; index < uses.size() && index < (maxExcerpts + 1) / 2; index++) {
                addApart(excerpts, uses.get(index).getFirst(), maxExcerpts);
            }

            List<String> searched = terms.stream().map(String::strip)
                    .filter(term -> TERM.matcher(term).matches() && !SECRET.matcher(term).find())
                    .distinct().limit(MAX_TERMS).toList();
            List<Searched> searches = new ArrayList<>();
            if (!searched.isEmpty()) {
                List<List<int[]>> matches = new ArrayList<>();
                List<List<String>> matchPaths = new ArrayList<>();
                search(searched, searches, matches, matchPaths);
                addMatches(excerpts, searched, matches, matchPaths, maxExcerpts);
            }

            for (List<Excerpt> file : uses) {
                file.forEach(excerpt -> addApart(excerpts, excerpt, maxExcerpts));
            }
            return new Read(excerpts, searches, !uses.isEmpty());
        }

        @Override
        public void close() {
            checkout.close();
        }

        // --- uses ------------------------------------------------------------------------------------------------

        /**
         * The windows where {@code text} uses what its imports at {@code importLines} bind, a window per run of nearby
         * uses; with no use, the first import itself, as {@code otherwise}.
         */
        private List<Excerpt> uses(String path, String text, List<Integer> importLines, Kind otherwise, String term) {
            Set<Integer> used = new TreeSet<>();
            for (int line : importLines) {
                used.addAll(ImportBindings.usages(language, text, ImportBindings.at(language, text, line)));
            }
            List<String> lines = text.replace("\r", "").lines().toList();
            Kind kind = term == null ? Kind.USE : Kind.MATCH;
            if (used.isEmpty()) {
                return List.of(window(path, lines, List.of(importLines.getFirst()), otherwise, term));
            }
            List<Excerpt> windows = new ArrayList<>();
            List<Integer> run = new ArrayList<>();
            for (int line : used) {
                if (!run.isEmpty() && line - run.getLast() > 2 * AROUND) {
                    windows.add(window(path, lines, run, kind, term));
                    run = new ArrayList<>();
                }
                run.add(line);
            }
            windows.add(window(path, lines, run, kind, term));
            return windows;
        }

        // --- the search --------------------------------------------------------------------------------------------

        private void search(List<String> terms, List<Searched> searches, List<List<int[]>> matches,
                List<List<String>> matchPaths) {
            List<Pattern> patterns = terms.stream().map(Code::pattern).toList();
            List<String> wildcards = terms.stream().map(Code::wildcard).toList();
            int[] filesMatched = new int[terms.size()];
            int[] linesMatched = new int[terms.size()];
            terms.forEach(term -> {
                matches.add(new ArrayList<>());
                matchPaths.add(new ArrayList<>());
            });
            int filesSearched = 0;
            for (Path file : files) {
                String path = ImportScanner.relative(checkout.root(), file);
                String text = ImportScanner.read(file);
                if (text == null) {
                    continue;
                }
                filesSearched++;
                String code = ImportBindings.blanked(language, text);
                String[] lines = code.split("\n", -1);
                boolean kept = false;
                for (int index = 0; index < terms.size(); index++) {
                    if (words(terms.get(index)).size() < 2 && !code.contains(terms.get(index))
                            && (wildcards.get(index) == null || !code.contains(wildcards.get(index)))) {
                        continue;
                    }
                    boolean found = false;
                    for (int line = 0; line < lines.length; line++) {
                        if (patterns.get(index).matcher(lines[line]).find()) {
                            found = true;
                            linesMatched[index]++;
                            if (matches.get(index).size() < MATCHES_KEPT) {
                                matches.get(index).add(new int[] {line + 1});
                                matchPaths.get(index).add(path);
                                kept = true;
                            }
                        }
                    }
                    if (found) {
                        filesMatched[index]++;
                    }
                }
                if (kept) {
                    texts.put(path, text);
                }
            }
            for (int index = 0; index < terms.size(); index++) {
                searches.add(new Searched(terms.get(index), filesSearched, filesMatched[index], linesMatched[index],
                        complete));
            }
        }

        /**
         * Where {@code term} is written as a whole name; a qualified type also where its package's wildcard import
         * ({@code import java.nio.file.*}) declares it, so finding none rules out a field of that type.
         */
        private static Pattern pattern(String term) {
            List<String> words = words(term);
            if (words.size() >= 2) {
                // Several words (an advisory's "Cache Component" named CacheComponent): however the code writes them —
                // cacheComponents, CACHE_COMPONENT, cache-component — in any case and either number.
                String spelled = String.join("[_-]?", words.stream()
                        .map(word -> Pattern.quote(word.endsWith("s") ? word.substring(0, word.length() - 1) : word)
                                + "s?")
                        .toList());
                return Pattern.compile("(?<![A-Za-z0-9_$])" + spelled + "(?![A-Za-z0-9_$])", Pattern.CASE_INSENSITIVE);
            }
            String named = "(?<![A-Za-z0-9_$])" + Pattern.quote(term) + "(?![A-Za-z0-9_$])";
            String wildcard = wildcard(term);
            return Pattern.compile(wildcard == null ? named
                    : named + "|(?<![A-Za-z0-9_$.])" + Pattern.quote(wildcard) + "(?![A-Za-z0-9_$])");
        }

        /** The words of an undotted name: {@code cacheComponents} → cache, Components; {@code app_router} → app, router. */
        private static List<String> words(String term) {
            if (term.contains(".")) {
                return List.of(term);
            }
            return Stream.of(term.split("[_-]+|(?<=[a-z0-9])(?=[A-Z])")).filter(word -> !word.isEmpty()).toList();
        }

        /** {@code java.nio.file.Path} → {@code java.nio.file.*}; null for a term that isn't a qualified type. */
        private static String wildcard(String term) {
            Matcher qualified = QUALIFIED_TYPE.matcher(term);
            return qualified.matches() ? qualified.group(1) + "*" : null;
        }

        /** Each name's places in turn, files not yet shown first, until the cap. */
        private void addMatches(List<Excerpt> excerpts, List<String> terms, List<List<int[]>> matches,
                List<List<String>> matchPaths, int maxExcerpts) {
            Set<String> shown = new TreeSet<>();
            excerpts.forEach(excerpt -> shown.add(excerpt.path()));
            List<Iterator<Integer>> order = new ArrayList<>();
            for (int index = 0; index < terms.size(); index++) {
                List<String> paths = matchPaths.get(index);
                List<Integer> indexes = new ArrayList<>();
                for (int at = 0; at < paths.size(); at++) {
                    if (!shown.contains(paths.get(at))) {
                        indexes.add(at);
                    }
                }
                for (int at = 0; at < paths.size(); at++) {
                    if (shown.contains(paths.get(at))) {
                        indexes.add(at);
                    }
                }
                order.add(indexes.iterator());
            }
            int[] taken = new int[terms.size()];
            boolean progress = true;
            while (progress && excerpts.size() < maxExcerpts) {
                progress = false;
                for (int index = 0; index < terms.size() && excerpts.size() < maxExcerpts; index++) {
                    Iterator<Integer> next = order.get(index);
                    while (taken[index] < MATCHES_PER_TERM && next.hasNext()) {
                        int at = next.next();
                        String path = matchPaths.get(index).get(at);
                        Excerpt excerpt = match(path, matches.get(index).get(at)[0], terms.get(index));
                        if (excerpt != null && addApart(excerpts, excerpt, maxExcerpts)) {
                            taken[index]++;
                            progress = true;
                            break;
                        }
                    }
                }
            }
        }

        /** Where a name was found; on an import, followed to where the file uses what that import binds. */
        private Excerpt match(String path, int line, String term) {
            String text = text(path);
            if (text == null) {
                return null;
            }
            String[] code = ImportBindings.blanked(language, text).split("\n", -1);
            if (line <= code.length && ImportBindings.isImport(language, code[line - 1])) {
                return uses(path, text, List.of(line), Kind.MATCH, term).getFirst();
            }
            return window(path, text.replace("\r", "").lines().toList(), List.of(line), Kind.MATCH, term);
        }

        // --- excerpts ----------------------------------------------------------------------------------------------

        /** Adds {@code excerpt} unless the cap is reached or it overlaps one already there; whether it was added. */
        private static boolean addApart(List<Excerpt> excerpts, Excerpt excerpt, int maxExcerpts) {
            if (excerpts.size() >= maxExcerpts || excerpts.stream().anyMatch(other -> other.path().equals(excerpt.path())
                    && other.startLine() <= lastLine(excerpt) && excerpt.startLine() <= lastLine(other))) {
                return false;
            }
            excerpts.add(excerpt);
            return true;
        }

        private static int lastLine(Excerpt excerpt) {
            return excerpt.startLine() + Math.max(excerpt.text().split("\n", -1).length, 1) - 1;
        }

        /**
         * The lines around {@code focus} (its first line is the excerpt's {@code line}), clipped to the file — the whole
         * file when it fits in {@link #MAX_EXCERPT_CHARS}, since a short file (a config) is often telling for what it
         * leaves out; while they run over the limit, the line farther from that first one goes first, and a single line
         * too long is cut.
         */
        private static Excerpt window(String path, List<String> lines, List<Integer> focus, Kind kind, String term) {
            int at = Math.min(Math.max(focus.getFirst(), 1), Math.max(lines.size(), 1));
            boolean whole = !lines.isEmpty() && length(lines, 1, lines.size()) <= MAX_EXCERPT_CHARS;
            int from = whole ? 1 : Math.max(1, at - AROUND);
            int to = whole ? lines.size() : Math.min(lines.size(), Math.max(at, focus.getLast()) + AROUND);
            while (length(lines, from, to) > MAX_EXCERPT_CHARS && (from < at || to > at)) {
                if (at - from >= to - at) {
                    from++;
                } else {
                    to--;
                }
            }
            String text = lines.isEmpty() ? "" : String.join("\n", lines.subList(from - 1, to));
            if (text.length() > MAX_EXCERPT_CHARS) {
                text = text.substring(0, MAX_EXCERPT_CHARS - 1) + "…";
            }
            return new Excerpt(path, from, at, text, kind, term);
        }

        private static int length(List<String> lines, int from, int to) {
            int total = Math.max(to - from, 0);
            for (int line = from; line <= to; line++) {
                total += lines.get(line - 1).length();
            }
            return total;
        }

        // --- the files ---------------------------------------------------------------------------------------------

        /** A file's text, or null when it isn't one of the files read. */
        private String text(String path) {
            String known = texts.get(path);
            if (known != null) {
                return known;
            }
            Path root = checkout.root();
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root) || !readable.contains(file)) {
                return null;
            }
            String text = ImportScanner.read(file);
            if (text != null) {
                texts.put(path, text);
            }
            return text;
        }

        private void walk() {
            Path root = checkout.root();
            Set<String> links = checkout.links();
            List<Path> found = new ArrayList<>();
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                        if (dir.equals(root)) {
                            return FileVisitResult.CONTINUE;
                        }
                        return attributes.isSymbolicLink()
                                || ImportScanner.SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                                || links.contains(ImportScanner.relative(root, dir))
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (attributes.isRegularFile() && !attributes.isSymbolicLink()
                                && language.equals(ImportScanner.language(file))
                                && !links.contains(ImportScanner.relative(root, file))
                                && !ImportScanner.secretLooking(file) && !ImportScanner.generated(file)) {
                            found.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException unreadable) {
                complete = false;
            }
            found.sort(null);
            long bytes = 0;
            for (Path file : found) {
                if (files.size() >= ImportScanner.MAX_FILES) {
                    complete = false;
                    break;
                }
                long size;
                try {
                    size = Files.size(file);
                } catch (IOException unreadable) {
                    continue;
                }
                if (size > ImportScanner.MAX_FILE_BYTES || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    complete = false;
                    continue;
                }
                if (bytes + size > ImportScanner.MAX_TOTAL_BYTES) {
                    complete = false;
                    break;
                }
                bytes += size;
                files.add(file);
                readable.add(file);
            }
        }
    }
}
