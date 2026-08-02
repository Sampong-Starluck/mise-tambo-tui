package com.sampong.tambo.mise;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import com.sampong.tambo.cli.CliProcessRunner;
import com.sampong.tambo.cli.CliResult;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * Executes the {@code mise} CLI as a subprocess and captures its output.
 * <p>
 * Every call blocks the calling thread until the process exits or the timeout elapses,
 * so callers running this from a UI render loop must dispatch it to a background thread.
 */
@Component
public class MiseCli {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final CliProcessRunner runner;
    /** Set by {@code --offline} on the command line; see {@link #offline()}. */
    private final boolean offline;

    /** Reads stdout/stderr on the same virtual-thread executor the rest of the app uses. */
    public MiseCli(@Qualifier("miseTaskExecutor") @NonNull AsyncTaskExecutor executor,
                   @NonNull CancelRegistry cancelRegistry, @NonNull ApplicationArguments arguments) {
        this.runner = new CliProcessRunner("mise", executor, cancelRegistry);
        this.offline = arguments.containsOption("offline");
    }

    /** True when the app was launched with {@code --offline}: no mise command here may touch the network. */
    public boolean offline() {
        return offline;
    }

    public CliResult run(@NonNull List<String> args) {
        return run(args, DEFAULT_TIMEOUT);
    }

    public CliResult run(@NonNull List<String> args, @NonNull Duration timeout) {
        return runner.run(args, timeout, this::applyOfflineEnv);
    }

    /**
     * Runs {@code mise} with stdout and stderr merged, invoking {@code onLine} for every
     * line as it is produced so callers can render live progress. Lines are stripped of
     * ANSI escapes and carriage returns before delivery. {@code onLine} is called on a
     * background reader thread — callers must marshal to their own thread if needed.
     * The returned {@link CliResult} carries the full combined output as stdout.
     */
    public CliResult runStreaming(@NonNull List<String> args, @NonNull Duration timeout, @NonNull Consumer<String> onLine) {
        return runStreaming(args, timeout, onLine, null);
    }

    /**
     * Like {@link #runStreaming(List, Duration, Consumer)} but registers the running
     * process under {@code cancelKey} in the {@link CancelRegistry} so the UI can
     * terminate it early. A cancelled process exits non-zero, surfacing as a failed
     * {@link CliResult}. Pass {@code null} to opt out of cancellation.
     */
    public CliResult runStreaming(@NonNull List<String> args, @NonNull Duration timeout,
                                  @NonNull Consumer<String> onLine, @Nullable String cancelKey) {
        return runner.runStreaming(args, timeout, onLine, cancelKey, this::applyOfflineEnv);
    }

    /** Forces offline mode when {@code --offline} is set on the command line. */
    private void applyOfflineEnv(ProcessBuilder builder) {
        if (offline) {
            builder.environment().put("MISE_OFFLINE", "1");
        }
    }
}
