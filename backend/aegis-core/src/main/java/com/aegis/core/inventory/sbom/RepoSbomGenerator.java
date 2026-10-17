package com.aegis.core.inventory.sbom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.aegis.core.inventory.domain.ImportScan;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.sbom.CommandRunner.CommandResult;
import com.aegis.core.platform.common.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates a CycloneDX SBOM from a source repository by shallow-cloning it and running <b>syft</b>
 * over the checkout, then parsing syft's output into the same {@link CycloneDxBom} an uploaded file
 * produces — so generation is just another way to reach {@link SbomImportService}. Any host on the
 * allow-list works (GitHub, GitLab including nested groups, Bitbucket, a self-hosted server, …), public
 * or private — with a pasted access token, or a credential from a host integration such as the GitHub App
 * ({@link RepositoryReadAccess}); {@link SbomGenerationService} decides which.
 *
 * <p><b>Security.</b> Syft is a static cataloguer: it reads lockfiles and manifests and never runs a
 * package manager or the repository's own code. The remaining surface is the clone and the file walk,
 * bounded on every axis:
 * <ul>
 *   <li>the host must be on the allow-list, and the clone URL is <em>reconstructed</em> from validated
 *       path segments (and an optionally validated ref) rather than passing the caller's string to
 *       {@code git} — so no argument or protocol injection is possible;</li>
 *   <li>a credential's secret is fed to git through {@code GIT_ASKPASS} from the environment — never in
 *       the argument list or {@code .git/config} on disk (only a non-secret username sits in the URL) —
 *       and it is scrubbed from any error surfaced to the caller;</li>
 *   <li>{@code git} is invoked shallow, single-branch, with file/ext protocols disabled and terminal
 *       credential prompts off (so a wrong/absent token fails fast rather than hanging), and gives up on a
 *       transfer that stalls below 1 KB/s for 30 s instead of waiting out the clone timeout;</li>
 *   <li>it fetches only what syft could read: a partial clone brings the file tree without contents, and a
 *       sparse checkout then fetches every file except images, video, audio, fonts and documents
 *       ({@link #NOT_SCANNED}), in one batch — an application's assets are most of its weight (21 of one
 *       repository's 23 MB were images, and the full clone outran the timeout on a slow link);</li>
 *   <li>the checkout is size-capped before syft walks it, syft's output is size-capped before it is
 *       parsed, and both steps run under hard timeouts, in a fresh temp directory that is always deleted.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "aegis.inventory.sbom-generation.enabled", havingValue = "true", matchIfMissing = true)
class RepoSbomGenerator {

    private static final Logger log = LoggerFactory.getLogger(RepoSbomGenerator.class);

    /**
     * File extensions syft never reads packages from: images, video, audio, fonts and documents. Archives stay
     * (syft looks inside them for Java packages), and so does everything else, so no ecosystem goes missing.
     */
    static final List<String> NOT_SCANNED = List.of(
            "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "webp", "ico", "icns", "heic", "heif", "avif", "svg",
            "psd", "ai", "sketch", "fig", "xcf",
            "mp4", "mov", "avi", "mkv", "webm", "m4v", "wmv", "flv", "mp3", "wav", "ogg", "flac", "aac", "m4a",
            "woff", "woff2", "ttf", "otf", "eot",
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "key", "numbers", "pages");

    /** The sparse checkout: everything, except {@link #NOT_SCANNED} in any case ({@code !*.[pP][nN][gG]}). */
    static final List<String> SPARSE_PATTERNS = Stream.concat(Stream.of("/*"),
            NOT_SCANNED.stream().map(extension -> "!*." + anyCase(extension))).toList();

    private final SbomGenerationProperties properties;
    private final CommandRunner commands;
    // Jackson 3 reads the com.fasterxml annotations on CycloneDxBom; a plain mapper is all this needs
    // (same pattern as the OSV parser), which keeps this component free of any bean it must be handed.
    private final ObjectMapper mapper = new ObjectMapper();

    RepoSbomGenerator(SbomGenerationProperties properties, CommandRunner commands) {
        this.properties = properties;
        this.commands = commands;
    }

    /**
     * Clone {@code repo} (at {@code ref}, or its default branch when null) with {@code access}, scan it with
     * syft, and return the CycloneDX BOM.
     */
    CycloneDxBom generate(RepositoryUrl repo, String ref, CloneAccess access) {
        return read(repo, ref, access).layout().bom();
    }

    /**
     * What an import from a repository read: its layout — syft's BOM, with what the JavaScript lockfiles in the same
     * checkout say about the packages syft found in them ({@link JsLockfiles}) — and what its code imports
     * ({@link ImportScanner}, E11-3).
     */
    record RepositoryScan(RepositoryLayout layout, ImportScan imports) {
    }

    /** Clone {@code repo} as {@link #generate} does, scan it with syft, read its lockfiles, and read its code's imports. */
    RepositoryScan read(RepositoryUrl repo, String ref, CloneAccess access) {
        String branch = RepositoryUrl.normalizeRef(ref);
        Path work;
        try {
            work = Files.createTempDirectory("aegis-sbom-");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create a workspace for SBOM generation", ex);
        }
        try {
            Path checkout = work.resolve("repo");
            clone(repo, branch, access, checkout, work);
            enforceSizeCap(checkout);
            Path sbom = work.resolve("sbom.cdx.json");
            scan(checkout, sbom, work);
            RepositoryLayout layout = RepositoryLayout.of(parse(sbom), JsLockfiles.read(checkout));
            ImportScan imports = ImportScanner.scan(checkout, symbolicLinks(checkout, work), revision(checkout, work));
            return new RepositoryScan(layout, imports);
        } finally {
            deleteRecursively(work);
        }
    }

    // --- steps ------------------------------------------------------------------------------------

    private void clone(RepositoryUrl repo, String branch, CloneAccess access, Path checkout, Path work) {
        String token = token(access);
        Map<String, String> env = gitEnv(work, token);
        // One deadline for all three steps: the clone timeout covers getting the files, however it's done.
        Instant deadline = Instant.now().plus(properties.cloneTimeout());

        // The file tree only: contents come with the checkout, and only for what syft can read. A host that
        // can't filter sends everything, as a plain shallow clone would.
        cloneTree(repo, branch, access, checkout, work, env, deadline);

        List<String> sparse = git("-C", checkout.toString(), "sparse-checkout", "set", "--no-cone");
        sparse.addAll(SPARSE_PATTERNS);
        runGit(sparse, work, env, deadline, repo, branch, access, token);
        // Fetches the contents of every file the patterns keep, in one batch, with the clone's credential.
        runGit(git("-C", checkout.toString(), "checkout"), work, env, deadline, repo, branch, access, token);
        if (!Files.isDirectory(checkout)) {
            throw cloneFailure("", repo, branch, access);
        }
    }

    /**
     * The repository at {@code revision}, with only the files {@code patterns} keep (E11-5): the file tree without
     * contents, that commit fetched when the branch has moved on since, then a sparse checkout of those patterns — one
     * batch, nothing else fetched. Same hardening, credential handling and deadline as {@link #read}; the caller closes
     * it, which deletes it. A repository it can't clone fails as an import would, and a commit the host no longer has
     * with {@link CommitGone}.
     */
    Checkout checkout(RepositoryUrl repo, String ref, String revision, List<String> patterns, CloneAccess access) {
        String branch = RepositoryUrl.normalizeRef(ref);
        Path work;
        try {
            work = Files.createTempDirectory("aegis-code-");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create a workspace to read the code", ex);
        }
        try {
            Path root = work.resolve("repo");
            String token = token(access);
            Map<String, String> env = gitEnv(work, token);
            Instant deadline = Instant.now().plus(properties.cloneTimeout());
            cloneTree(repo, branch, access, root, work, env, deadline);
            if (!revision.equals(revision(root, work))) {
                try {
                    runGit(git("-C", root.toString(), "fetch", "--depth", "1", "--no-tags", "origin", revision),
                            work, env, deadline, repo, branch, access, token);
                } catch (ApiExceptions.BadRequest gone) {
                    throw new CommitGone();
                }
            }
            List<String> sparse = git("-C", root.toString(), "sparse-checkout", "set", "--no-cone");
            sparse.addAll(patterns);
            runGit(sparse, work, env, deadline, repo, branch, access, token);
            runGit(git("-C", root.toString(), "checkout", "--quiet", "--detach", revision), work, env, deadline, repo,
                    branch, access, token);
            if (!Files.isDirectory(root)) {
                throw cloneFailure("", repo, branch, access);
            }
            enforceSizeCap(root);
            return new Checkout(work, root, symbolicLinks(root, work));
        } catch (RuntimeException failed) {
            deleteRecursively(work);
            throw failed;
        }
    }

    /** A checkout {@link #checkout} made: its files under {@code root}; closing it deletes it. */
    record Checkout(Path work, Path root, Set<String> links) implements AutoCloseable {

        @Override
        public void close() {
            deleteRecursively(work);
        }
    }

    /** The commit an import read is no longer one the host serves: the history was rewritten. */
    static final class CommitGone extends RuntimeException {
        CommitGone() {
            super(null, null, false, false);
        }
    }

    /**
     * {@code git clone} of the file tree without contents, shallow and single-branch: the credential's NON-secret
     * username in the URL, its secret to git through {@code GIT_ASKPASS} from the environment — never in argv or
     * {@code .git/config}.
     */
    private void cloneTree(RepositoryUrl repo, String branch, CloneAccess access, Path checkout, Path work,
            Map<String, String> env, Instant deadline) {
        CloneCredential credential = access.credential();
        String cloneUrl = credential == null ? repo.canonical() + ".git"
                : "https://" + credential.username() + "@" + repo.host() + "/" + repo.path() + ".git";
        List<String> command = git("clone", "--depth", "1", "--single-branch", "--no-tags", "--filter=blob:none",
                "--no-checkout");
        if (branch != null) {
            command.add("--branch");
            command.add(branch);
        }
        command.add(cloneUrl);
        command.add(checkout.toString());
        runGit(command, work, env, deadline, repo, branch, access, token(access));
    }

    private static String token(CloneAccess access) {
        return access.credential() == null ? null : access.credential().secret();
    }

    /** git's environment: no prompts, no system or global config, and the askpass helper when there's a secret. */
    private static Map<String, String> gitEnv(Path work, String token) {
        Map<String, String> env = new HashMap<>(Map.of(
                "GIT_TERMINAL_PROMPT", "0", // never hang on a prompt; askpass (if any) supplies the credential
                "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "HOME", work.toString()));
        if (token != null) {
            env.put("GIT_ASKPASS", writeAskpass(work).toString());
            env.put("AEGIS_GIT_TOKEN", token);
        }
        return env;
    }

    /** The commit checked out, so a link to a line points at what was read; null when git can't say. */
    private String revision(Path checkout, Path work) {
        CommandResult result = localGit(work, git("-C", checkout.toString(), "rev-parse", "HEAD"));
        String revision = result == null || !result.succeeded() || result.stdout() == null ? "" : result.stdout().strip();
        return revision.matches("[0-9a-f]{40}|[0-9a-f]{64}") ? revision : null;
    }

    /**
     * The paths the repository records as symbolic links (mode 120000). The clone checks them out as plain files holding
     * only the target ({@code core.symlinks=false}), so the code scan is told to skip them. Empty when git can't say.
     */
    private Set<String> symbolicLinks(Path checkout, Path work) {
        CommandResult result = localGit(work, git("-C", checkout.toString(), "ls-files", "-s", "-z"));
        if (result == null || !result.succeeded() || result.stdout() == null) {
            return Set.of();
        }
        Set<String> links = new HashSet<>();
        for (String entry : result.stdout().split("\0")) {
            int tab = entry.indexOf('\t');
            if (entry.startsWith("120000 ") && tab > 0) {
                links.add(entry.substring(tab + 1));
            }
        }
        return links;
    }

    /** A git command that reads only the local checkout: no credential, a short timeout, and no failure of its own. */
    private CommandResult localGit(Path work, List<String> command) {
        Map<String, String> env = Map.of(
                "GIT_TERMINAL_PROMPT", "0",
                "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "HOME", work.toString());
        try {
            return commands.run(command, work, env, Duration.ofSeconds(30));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** A git command with the hardening every step gets. */
    private List<String> git(String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                properties.gitPath(),
                "-c", "protocol.file.allow=never",
                "-c", "protocol.ext.allow=never",
                "-c", "core.symlinks=false",
                // A stalled connection fails in 30 s rather than holding the request for the whole timeout.
                "-c", "http.lowSpeedLimit=1000",
                "-c", "http.lowSpeedTime=30"));
        command.addAll(List.of(arguments));
        return command;
    }

    private void runGit(List<String> command, Path work, Map<String, String> env, Instant deadline,
            RepositoryUrl repo, String branch, CloneAccess access, String token) {
        Duration left = Duration.between(Instant.now(), deadline);
        CommandResult result = left.isNegative() || left.isZero() ? null : run(command, work, env, left, "git");
        if (result == null || result.timedOut()) {
            throw new ApiExceptions.BadRequest("Cloning " + repo.canonical() + " timed out after "
                    + properties.cloneTimeout().toSeconds() + " seconds; " + repo.host()
                    + " may be slow or unreachable, so try again");
        }
        if (!result.succeeded()) {
            throw cloneFailure(scrub(result.stderr(), token), repo, branch, access);
        }
    }

    /** A wildmatch pattern matching {@code text} in any case: {@code mp4} → {@code [mM][pP]4}. */
    static String anyCase(String text) {
        StringBuilder pattern = new StringBuilder();
        for (char c : text.toCharArray()) {
            pattern.append(Character.isLetter(c)
                    ? "[" + Character.toLowerCase(c) + Character.toUpperCase(c) + "]" : String.valueOf(c));
        }
        return pattern.toString();
    }

    /** Turn git's stderr into what the person importing can act on; the raw detail only when nothing fits. */
    private static ApiExceptions.BadRequest cloneFailure(String stderr, RepositoryUrl repo, String branch,
            CloneAccess access) {
        String lower = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        if (branch != null && lower.contains("remote branch") && lower.contains("not found")) {
            return new ApiExceptions.BadRequest("The branch or tag '" + branch + "' doesn't exist in " + repo.canonical());
        }
        if (lower.contains("could not resolve host")) {
            return new ApiExceptions.BadRequest("Could not reach " + repo.host() + "; check the URL, or try again");
        }
        if (lower.contains("operation too slow") || lower.contains("timed out") || lower.contains("early eof")) {
            return new ApiExceptions.BadRequest("The connection to " + repo.host() + " stalled; try again");
        }
        if (lower.contains("returned error: 429")) {
            return new ApiExceptions.BadRequest(repo.host() + " is limiting requests"
                    + (access.credential() == null ? " without credentials; add an access token, or" : ";")
                    + " try again later");
        }
        boolean refused = lower.contains("could not read username") || lower.contains("authentication failed")
                || lower.contains("access denied") || lower.contains("invalid credentials")
                || lower.contains("invalid username or") || lower.contains("not found")
                || lower.contains("returned error: 401") || lower.contains("returned error: 403")
                || lower.contains("returned error: 404");
        if (refused) {
            return new ApiExceptions.BadRequest(switch (access.source()) {
                case TOKEN -> "The access token was refused for " + repo.canonical() + ": it needs read access to"
                        + " the repository's contents, or the repository doesn't exist";
                case INTEGRATION -> repo.canonical() + " could not be read with " + access.detail()
                        + "; check it has access to this repository";
                case ANONYMOUS -> repo.canonical() + " is private or doesn't exist"
                        + (access.detail() == null ? "" : " (" + access.detail() + ")")
                        + ". Add a read-only access token to import it";
            });
        }
        return new ApiExceptions.BadRequest("Could not clone the repository: " + errorLines(stderr));
    }

    private void enforceSizeCap(Path checkout) {
        long total = 0;
        try (Stream<Path> walk = Files.walk(checkout)) {
            for (Iterator<Path> it = walk.iterator(); it.hasNext(); ) {
                Path path = it.next();
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    total += Files.size(path);
                    if (total > properties.maxRepoBytes()) {
                        throw new ApiExceptions.BadRequest("The repository is too large to scan");
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not measure the cloned repository", ex);
        }
    }

    private void scan(Path checkout, Path sbom, Path work) {
        List<String> command = List.of(
                properties.syftPath(), "scan", "dir:" + checkout, "--quiet",
                "--output", "cyclonedx-json=" + sbom);
        Map<String, String> env = Map.of(
                "HOME", work.toString(),
                "XDG_CACHE_HOME", work.resolve("cache").toString(),
                "SYFT_CHECK_FOR_APP_UPDATE", "false");

        CommandResult result = run(command, work, env, properties.scanTimeout(), "syft");
        if (result.timedOut()) {
            throw new ApiExceptions.BadRequest("Scanning the repository timed out");
        }
        if (!result.succeeded() || !Files.isRegularFile(sbom)) {
            throw new ApiExceptions.BadRequest("Could not generate an SBOM from the repository: "
                    + errorLines(result.stderr()));
        }
    }

    private CycloneDxBom parse(Path sbom) {
        try {
            long size = Files.size(sbom);
            if (size > properties.maxSbomBytes()) {
                throw new ApiExceptions.BadRequest("The generated SBOM is too large to import");
            }
            byte[] json = Files.readAllBytes(sbom);
            return mapper.readValue(json, CycloneDxBom.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read the generated SBOM", ex);
        } catch (RuntimeException ex) {
            throw new ApiExceptions.BadRequest("The generated SBOM was not valid CycloneDX JSON");
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** Write a one-line askpass helper that echoes the token from the environment; owner-only, executable. */
    private static Path writeAskpass(Path work) {
        try {
            Path script = work.resolve("askpass.sh");
            Files.writeString(script, "#!/bin/sh\nprintf '%s' \"$AEGIS_GIT_TOKEN\"\n");
            PosixFileAttributeView posix = Files.getFileAttributeView(script, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } else {
                // Windows has ACLs rather than POSIX permissions. Keep the helper owner-only there too.
                AclFileAttributeView acl = Files.getFileAttributeView(script, AclFileAttributeView.class);
                if (acl == null) {
                    throw new IOException("The filesystem cannot restrict access to the credential helper");
                }
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            }
            return script;
        } catch (IOException ex) {
            throw new IllegalStateException("Could not prepare credentials for the clone", ex);
        }
    }

    private CommandResult run(List<String> command, Path work, Map<String, String> env, Duration timeout,
            String tool) {
        try {
            return commands.run(command, work, env, timeout);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(tool + " was interrupted", ex);
        } catch (Exception ex) {
            // A missing binary lands here (e.g. syft not installed) — surface it as a clear 400.
            throw new ApiExceptions.BadRequest("Could not run " + tool
                    + " — SBOM generation may not be available on this server");
        }
    }

    /** Never let a token reach an error message shown to the caller or the logs. */
    private static String scrub(String text, String token) {
        return text == null || token == null || token.isEmpty() ? text : text.replace(token, "***");
    }

    /**
     * The lines of a command's stderr that actually explain a failure — git prints a "Cloning into …"
     * progress line first and the real {@code fatal:}/{@code remote:} reason after, so returning the
     * first line hid the cause. Prefer the error-carrying lines; fall back to the last line.
     */
    private static String errorLines(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "(no output)";
        }
        List<String> lines = stderr.strip().lines().map(String::strip).filter(line -> !line.isBlank()).toList();
        List<String> meaningful = lines.stream()
                .filter(line -> {
                    String lower = line.toLowerCase(Locale.ROOT);
                    return lower.startsWith("fatal:") || lower.startsWith("error:") || lower.startsWith("remote:");
                })
                .toList();
        List<String> chosen = meaningful.isEmpty() ? List.of(lines.get(lines.size() - 1)) : meaningful;
        String joined = String.join("; ", chosen);
        return joined.length() > 400 ? joined.substring(0, 400) + "…" : joined;
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.warn("could not delete {} during SBOM cleanup: {}", path, ex.toString());
                }
            });
        } catch (IOException ex) {
            log.warn("could not clean up SBOM workspace {}: {}", dir, ex.toString());
        }
    }

}
