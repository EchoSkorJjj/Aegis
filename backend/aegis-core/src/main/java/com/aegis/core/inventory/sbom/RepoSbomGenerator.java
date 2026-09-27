package com.aegis.core.inventory.sbom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
            return parse(sbom);
        } finally {
            deleteRecursively(work);
        }
    }

    // --- steps ------------------------------------------------------------------------------------

    private void clone(RepositoryUrl repo, String branch, CloneAccess access, Path checkout, Path work) {
        // With a credential, put its NON-secret username in the URL and hand the secret to git via
        // GIT_ASKPASS (read from the environment) — it never lands in argv or .git/config.
        CloneCredential credential = access.credential();
        String token = credential == null ? null : credential.secret();
        String cloneUrl = credential == null ? repo.canonical() + ".git"
                : "https://" + credential.username() + "@" + repo.host() + "/" + repo.path() + ".git";

        Map<String, String> env = new HashMap<>(Map.of(
                "GIT_TERMINAL_PROMPT", "0", // never hang on a prompt; askpass (if any) supplies the credential
                "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "HOME", work.toString()));
        if (token != null) {
            env.put("GIT_ASKPASS", writeAskpass(work).toString());
            env.put("AEGIS_GIT_TOKEN", token);
        }
        // One deadline for all three steps: the clone timeout covers getting the files, however it's done.
        Instant deadline = Instant.now().plus(properties.cloneTimeout());

        // The file tree only: contents come with the checkout, and only for what syft can read. A host that
        // can't filter sends everything, as a plain shallow clone would.
        List<String> command = git("clone", "--depth", "1", "--single-branch", "--no-tags", "--filter=blob:none",
                "--no-checkout");
        if (branch != null) {
            command.add("--branch");
            command.add(branch);
        }
        command.add(cloneUrl);
        command.add(checkout.toString());
        runGit(command, work, env, deadline, repo, branch, access, token);

        List<String> sparse = git("-C", checkout.toString(), "sparse-checkout", "set", "--no-cone");
        sparse.addAll(SPARSE_PATTERNS);
        runGit(sparse, work, env, deadline, repo, branch, access, token);
        // Fetches the contents of every file the patterns keep, in one batch, with the clone's credential.
        runGit(git("-C", checkout.toString(), "checkout"), work, env, deadline, repo, branch, access, token);
        if (!Files.isDirectory(checkout)) {
            throw cloneFailure("", repo, branch, access);
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
            Files.setPosixFilePermissions(script, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
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
