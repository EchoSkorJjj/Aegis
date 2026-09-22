package com.aegis.core.inventory.sbom;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs one external command to completion under a hard timeout. Abstracted from the SBOM generator so
 * the generator's logic (URL validation, argument construction, error handling, cleanup) is unit-tested
 * with a fake runner and never actually forks {@code git} or {@code syft}.
 */
interface CommandRunner {

    /**
     * Run {@code command} in {@code workingDir} with exactly {@code env} as its environment, killing it
     * if it outruns {@code timeout}. Never throws for a non-zero exit — that is reported in the result.
     */
    CommandResult run(List<String> command, Path workingDir, Map<String, String> env, Duration timeout)
            throws Exception;

    record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }
}
