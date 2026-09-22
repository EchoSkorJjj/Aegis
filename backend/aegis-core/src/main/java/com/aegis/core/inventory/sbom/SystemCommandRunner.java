package com.aegis.core.inventory.sbom;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * The production {@link CommandRunner}: a real {@link ProcessBuilder}. Output is redirected to files in
 * the working directory (not pipes) so a chatty command can never deadlock the JVM by filling a pipe
 * buffer, and only a capped prefix is read back for diagnostics — the real SBOM syft produces is written
 * to its own file, not captured here. The child runs with a minimal, explicit environment.
 */
@Component
class SystemCommandRunner implements CommandRunner {

    /** How much of a command's stdout/stderr to keep for error messages; the real output goes to files. */
    private static final int MAX_CAPTURED_BYTES = 64 * 1024;

    @Override
    public CommandResult run(List<String> command, Path workingDir, Map<String, String> env, Duration timeout)
            throws IOException, InterruptedException {
        Path out = Files.createTempFile(workingDir, "cmd-out", ".log");
        Path err = Files.createTempFile(workingDir, "cmd-err", ".log");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectOutput(out.toFile())
                .redirectError(err.toFile());
        // Start from nothing and add only PATH plus the caller's variables, so no inherited secret or
        // config (tokens, git identity) leaks into the child.
        builder.environment().clear();
        builder.environment().put("PATH", System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
        builder.environment().putAll(env);

        Process process = builder.start();
        process.getOutputStream().close(); // no stdin
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new CommandResult(-1, "", capped(err), true);
            }
            return new CommandResult(process.exitValue(), capped(out), capped(err), false);
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    private static String capped(Path file) throws IOException {
        if (!Files.exists(file)) {
            return "";
        }
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(MAX_CAPTURED_BYTES), StandardCharsets.UTF_8);
        }
    }
}
