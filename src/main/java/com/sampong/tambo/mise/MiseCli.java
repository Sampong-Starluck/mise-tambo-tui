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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
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

    /** ANSI escape sequences (colors, cursor movement) that mise may emit even when piped. */
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[;\\d]*[ -/]*[@-~]");

    private final AsyncTaskExecutor executor;
    private final CancelRegistry cancelRegistry;

    /** Reads stdout/stderr on the same virtual-thread executor the rest of the app uses. */
    public MiseCli(@Qualifier("miseTaskExecutor") @NonNull AsyncTaskExecutor executor,
                   @NonNull CancelRegistry cancelRegistry) {
        this.executor = executor;
        this.cancelRegistry = cancelRegistry;
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
            process = new ProcessBuilder(command).start();
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

            return new Result(process.exitValue(), stdoutFuture.join(), stderrFuture.join());
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
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
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
                reader.join();
                return new Result(-1, capturedText(captured),
                        "Timed out after " + timeout.getSeconds() + "s: mise " + String.join(" ", args));
            }
            reader.join();
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
