package com.sampong.tambo.mise;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes the {@code mise} CLI as a subprocess and captures its output.
 * <p>
 * Every call blocks the calling thread until the process exits or the timeout elapses,
 * so callers running this from a UI render loop must dispatch it to a background thread.
 */
@Slf4j
@Component
public class MiseCli {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    /**
     * How long to wait for buffered output to drain once the tracked process has
     * already exited. A task command can hand off to a detached background daemon
     * (e.g. {@code mvnd}, which keeps a JVM warm across builds) that inherits our
     * end of the output pipe and holds it open indefinitely by design. Without a
     * cap, waiting for that pipe to see EOF would block forever even though the
     * command we launched has already finished.
     */
    private static final Duration READER_DRAIN_GRACE = Duration.ofSeconds(2);

    /** ANSI escape sequences (colors, cursor movement) that mise may emit even when piped. */
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[;\\d]*[ -/]*[@-~]");

    private final AsyncTaskExecutor executor;
    private final CancelRegistry cancelRegistry;
    /** Set by {@code --offline} on the command line; see {@link #offline()}. */
    private final boolean offline;

    /** Reads stdout/stderr on the same virtual-thread executor the rest of the app uses. */
    public MiseCli(@Qualifier("miseTaskExecutor") @NonNull AsyncTaskExecutor executor,
                   @NonNull CancelRegistry cancelRegistry, @NonNull ApplicationArguments arguments) {
        this.executor = executor;
        this.cancelRegistry = cancelRegistry;
        this.offline = arguments.containsOption("offline");
    }

    /** True when the app was launched with {@code --offline}: no mise command here may touch the network. */
    public boolean offline() {
        return offline;
    }

    /** The result of running a {@code mise} subcommand. */
    public record Result(int exitCode, String stdout, String stderr) {

        public boolean ok() {
            return exitCode == 0;
        }

        /** The first non-blank line of stderr, falling back to stdout, for compact log lines. */
        public String summaryLine() {
            String source = !stderr.isBlank() ? stderr : stdout;
            for (String line : source.split("\n")) {
                if (!line.isBlank()) {
                    return line.strip();
                }
            }
            return "";
        }
    }

    public Result run(@NonNull List<String> args) {
        return run(args, DEFAULT_TIMEOUT);
    }

    public Result run(@NonNull List<String> args, @NonNull Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add("mise");
        command.addAll(args);

        Process process;
        try {
            process = newProcessBuilder(command).start();
        } catch (IOException e) {
            return new Result(-1, "", "Failed to launch mise: " + e.getMessage());
        }

        try {
            process.getOutputStream().close();

            CompletableFuture<String> stdoutFuture =
                    CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()), executor);
            CompletableFuture<String> stderrFuture =
                    CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()), executor);

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyTree(process);
                return new Result(-1, "", "Timed out after " + timeout.getSeconds() + "s: mise " + String.join(" ", args));
            }

            return new Result(process.exitValue(), drain(stdoutFuture), drain(stderrFuture));
        } catch (IOException e) {
            return new Result(-1, "", "Failed to run mise: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyTree(process);
            return new Result(-1, "", "Interrupted while running mise " + String.join(" ", args));
        }
    }

    /**
     * Runs {@code mise} with stdout and stderr merged, invoking {@code onLine} for every
     * line as it is produced so callers can render live progress. Lines are stripped of
     * ANSI escapes and carriage returns before delivery. {@code onLine} is called on a
     * background reader thread — callers must marshal to their own thread if needed.
     * The returned {@link Result} carries the full combined output as stdout.
     */
    public Result runStreaming(@NonNull List<String> args, @NonNull Duration timeout, @NonNull Consumer<String> onLine) {
        return runStreaming(args, timeout, onLine, null);
    }

    /**
     * Like {@link #runStreaming(List, Duration, Consumer)} but registers the running
     * process under {@code cancelKey} in the {@link CancelRegistry} so the UI can
     * terminate it early. A cancelled process exits non-zero, surfacing as a failed
     * {@link Result}. Pass {@code null} to opt out of cancellation.
     */
    public Result runStreaming(@NonNull List<String> args, @NonNull Duration timeout,
                               @NonNull Consumer<String> onLine, @Nullable String cancelKey) {
        List<String> command = new ArrayList<>();
        command.add("mise");
        command.addAll(args);

        Process process;
        try {
            process = newProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            return new Result(-1, "", "Failed to launch mise: " + e.getMessage());
        }

        if (cancelKey != null) {
            cancelRegistry.register(cancelKey, process);
        }

        StringBuilder captured = new StringBuilder();
        try {
            process.getOutputStream().close();

            CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        String clean = cleanLine(line);
                        synchronized (captured) {
                            captured.append(clean).append('\n');
                        }
                        onLine.accept(clean);
                    }
                } catch (IOException e) {
                    log.debug("Stream closed while reading mise output: {}", e.getMessage());
                }
            }, executor);

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyTree(process);
                drainReader(reader);
                return new Result(-1, capturedText(captured),
                        "Timed out after " + timeout.getSeconds() + "s: mise " + String.join(" ", args));
            }
            drainReader(reader);
            return new Result(process.exitValue(), capturedText(captured), "");
        } catch (IOException e) {
            return new Result(-1, capturedText(captured), "Failed to run mise: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyTree(process);
            return new Result(-1, capturedText(captured), "Interrupted while running mise " + String.join(" ", args));
        } finally {
            if (cancelKey != null) {
                cancelRegistry.deregister(cancelKey);
            }
        }
    }

    /** Builds the process for a {@code mise} invocation, forcing offline mode when {@code --offline} is set. */
    private ProcessBuilder newProcessBuilder(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (offline) {
            builder.environment().put("MISE_OFFLINE", "1");
        }
        return builder;
    }

    /**
     * Forcibly kills a process and everything it spawned. {@code mise} typically
     * forks a shell or build tool to do the real work (e.g. {@code mise run});
     * killing only the direct child leaves that descendant running and holding
     * the output pipe open, which hangs the reader thread waiting for an EOF
     * that never comes.
     */
    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * Waits briefly for the streaming reader to finish once the tracked process
     * has already exited, rather than joining it unboundedly. If a detached
     * daemon is still holding the pipe open, the reader thread is left running
     * in the background (a cheap virtual thread) instead of hanging the caller.
     */
    private static void drainReader(CompletableFuture<Void> reader) {
        try {
            reader.get(READER_DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.debug("Output reader still active after process exit "
                    + "(likely a detached daemon holding the pipe open); proceeding without it");
        } catch (ExecutionException e) {
            log.debug("Output reader failed: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Like {@link #drainReader} but for the non-streaming {@link #run} path: waits
     * briefly for the captured stream to finish, falling back to empty output
     * rather than blocking forever on a pipe a detached daemon still holds open.
     */
    private static String drain(CompletableFuture<String> future) {
        try {
            return future.get(READER_DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.debug("Output stream still open after process exit "
                    + "(likely a detached daemon holding the pipe open); returning partial output");
            return "";
        } catch (ExecutionException e) {
            log.debug("Output stream failed: {}", e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** Strips ANSI escapes and keeps only the final state of {@code \r}-overwritten progress lines. */
    private static String cleanLine(String line) {
        String noAnsi = ANSI.matcher(line).replaceAll("");
        int lastCr = noAnsi.lastIndexOf('\r');
        return (lastCr >= 0 ? noAnsi.substring(lastCr + 1) : noAnsi).stripTrailing();
    }

    private static String capturedText(StringBuilder captured) {
        synchronized (captured) {
            return captured.toString();
        }
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Failed to read mise output stream: {}", e.getMessage());
            return "";
        }
    }
}
