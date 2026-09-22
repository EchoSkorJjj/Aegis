package com.aegis.core.inventory.sbom;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.aegis.core.common.ApiExceptions;
import com.aegis.core.inventory.sbom.CommandRunner.CommandResult;
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
 * or — with an access token — private.
 *
 * <p><b>Security.</b> Syft is a static cataloguer: it reads lockfiles and manifests and never runs a
 * package manager or the repository's own code. The remaining surface is the clone and the file walk,
 * bounded on every axis:
 * <ul>
 *   <li>the host must be on the allow-list, and the clone URL is <em>reconstructed</em> from validated
 *       path segments (and an optionally validated ref) rather than passing the caller's string to
 *       {@code git} — so no argument or protocol injection is possible;</li>
 *   <li>a private-repo access token is fed to git through {@code GIT_ASKPASS} from the environment —
 *       never in the argument list or {@code .git/config} on disk (only a non-secret username sits in
 *       the URL) — and it is scrubbed from any error surfaced to the caller;</li>
 *   <li>{@code git} is invoked shallow, single-branch, with file/ext protocols disabled and terminal
 *       credential prompts off (so a wrong/absent token fails fast rather than hanging);</li>
 *   <li>the checkout is size-capped before syft walks it, syft's output is size-capped before it is
 *       parsed, and both steps run under hard timeouts, in a fresh temp directory that is always deleted.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "aegis.inventory.sbom-generation.enabled", havingValue = "true", matchIfMissing = true)
class RepoSbomGenerator {

    private static final Logger log = LoggerFactory.getLogger(RepoSbomGenerator.class);

    // A repository path segment: starts alphanumeric, then a bounded run of the characters hosts allow.
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    // A branch/tag/ref: starts alphanumeric (never '-', which would look like a flag) then a bounded run.
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");

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
     * Clone {@code repoUrl} (optionally at {@code ref}, optionally authenticated with {@code token} for a
     * private repo), scan it with syft, and return the CycloneDX BOM.
     */
    CycloneDxBom generate(String repoUrl, String ref, String token) {
        Repo repo = parseRepo(repoUrl);
        String branch = normalizeRef(ref);
        String credential = token == null || token.isBlank() ? null : token.trim();
        Path work;
        try {
            work = Files.createTempDirectory("aegis-sbom-");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create a workspace for SBOM generation", ex);
        }
        try {
            Path checkout = work.resolve("repo");
            clone(repo, branch, credential, checkout, work);
            enforceSizeCap(checkout);
            Path sbom = work.resolve("sbom.cdx.json");
            scan(checkout, sbom, work);
            return parse(sbom);
        } finally {
            deleteRecursively(work);
        }
    }

    // --- validation -------------------------------------------------------------------------------

    private Repo parseRepo(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new ApiExceptions.BadRequest("A repository URL is required");
        }
        URI uri;
        try {
            uri = URI.create(repoUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiExceptions.BadRequest("That is not a valid repository URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ApiExceptions.BadRequest("Only https:// repository URLs are supported");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (properties.allowedHosts().stream().noneMatch(allowed -> allowed.equalsIgnoreCase(host))) {
            throw new ApiExceptions.BadRequest("This host is not allowed. Allowed hosts: " + properties.allowedHosts());
        }
        // Path is owner(/subgroup...)/repo — GitLab nests groups, so keep everything up to the last segment.
        String[] segments = Arrays.stream((uri.getPath() == null ? "" : uri.getPath()).split("/"))
                .filter(part -> !part.isBlank())
                .toArray(String[]::new);
        if (segments.length < 2) {
            throw new ApiExceptions.BadRequest("Expected a URL like https://" + host + "/owner/repository");
        }
        segments[segments.length - 1] = stripSuffix(segments[segments.length - 1]);
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new ApiExceptions.BadRequest("The repository path contains unsupported characters");
            }
        }
        return new Repo(host, String.join("/", segments));
    }

    private static String stripSuffix(String repo) {
        return repo.endsWith(".git") ? repo.substring(0, repo.length() - 4) : repo;
    }

    private String normalizeRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String trimmed = ref.trim();
        if (!REF.matcher(trimmed).matches()) {
            throw new ApiExceptions.BadRequest("That branch or tag name is not allowed");
        }
        return trimmed;
    }

    // --- steps ------------------------------------------------------------------------------------

    private void clone(Repo repo, String branch, String token, Path checkout, Path work) {
        // With a token, put a NON-secret username in the URL and hand the token to git via GIT_ASKPASS
        // (read from the environment) — the token never lands in argv or .git/config. The username is the
        // host's convention (GitHub ignores it; GitLab wants oauth2; Bitbucket wants x-token-auth).
        String cloneUrl = token == null ? repo.cloneUrl() : repo.cloneUrl(authUsername(repo.host()));

        List<String> command = new ArrayList<>(List.of(
                properties.gitPath(),
                "-c", "protocol.file.allow=never",
                "-c", "protocol.ext.allow=never",
                "-c", "core.symlinks=false",
                "clone", "--depth", "1", "--single-branch", "--no-tags"));
        if (branch != null) {
            command.add("--branch");
            command.add(branch);
        }
        command.add(cloneUrl);
        command.add(checkout.toString());

        Map<String, String> env = new HashMap<>(Map.of(
                "GIT_TERMINAL_PROMPT", "0", // never hang on a prompt; askpass (if any) supplies the credential
                "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "HOME", work.toString()));
        if (token != null) {
            env.put("GIT_ASKPASS", writeAskpass(work).toString());
            env.put("AEGIS_GIT_TOKEN", token);
        }

        CommandResult result = run(command, work, env, properties.cloneTimeout(), "git");
        if (result.timedOut()) {
            throw new ApiExceptions.BadRequest("Cloning the repository timed out");
        }
        if (!result.succeeded() || !Files.isDirectory(checkout)) {
            String detail = scrub(errorLines(result.stderr()), token);
            throw new ApiExceptions.BadRequest("Could not clone the repository (a private repo needs a token "
                    + "whose scope includes read access to this repository's contents): " + detail);
        }
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

    private CommandResult run(List<String> command, Path work, Map<String, String> env, java.time.Duration timeout,
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

    /** The username each host expects alongside a token: GitHub ignores it, GitLab wants oauth2, Bitbucket x-token-auth. */
    private static String authUsername(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.contains("gitlab")) {
            return "oauth2";
        }
        if (lower.contains("bitbucket")) {
            return "x-token-auth";
        }
        return "x-access-token"; // GitHub and the generic default
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

    private record Repo(String host, String repoPath) {
        String cloneUrl() {
            return "https://" + host + "/" + repoPath + ".git";
        }

        /** Clone URL carrying a non-secret username; the password (token) is supplied via GIT_ASKPASS. */
        String cloneUrl(String username) {
            return "https://" + username + "@" + host + "/" + repoPath + ".git";
        }
    }
}
