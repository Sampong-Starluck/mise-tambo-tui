package com.sampong.tambo.vfox;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import com.sampong.tambo.cli.CliProcessRunner;
import com.sampong.tambo.cli.CliResult;
import com.sampong.tambo.mise.CancelRegistry;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * Executes the {@code vfox} CLI as a subprocess and captures its output.
 * <p>
 * Every call blocks the calling thread until the process exits or the timeout elapses,
 * so callers running this from a UI render loop must dispatch it to a background thread.
 */
@Component
public class VfoxCli {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final CliProcessRunner runner;

    public VfoxCli(@Qualifier("miseTaskExecutor") @NonNull AsyncTaskExecutor executor,
                   @NonNull CancelRegistry cancelRegistry) {
        this.runner = new CliProcessRunner("vfox", executor, cancelRegistry);
    }

    public CliResult run(@NonNull List<String> args) {
        return runner.run(args, DEFAULT_TIMEOUT, null);
    }

    public CliResult run(@NonNull List<String> args, @NonNull Duration timeout) {
        return runner.run(args, timeout, null);
    }

    /**
     * Runs {@code vfox} with stdout and stderr merged, invoking {@code onLine} for every
     * line as it is produced so callers can render live progress. The returned
     * {@link CliResult} carries the full combined output as stdout.
     */
    public CliResult runStreaming(@NonNull List<String> args, @NonNull Duration timeout,
                                  @NonNull Consumer<String> onLine, @Nullable String cancelKey) {
        return runner.runStreaming(args, timeout, onLine, cancelKey, null);
    }
}
