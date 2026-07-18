package com.sampong.tambo.mise;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Executes the {@code mise} CLI as a subprocess and captures its output.
 * <p>
 * Every call blocks the calling thread until the process exits or the timeout elapses,
 * so callers running this from a UI render loop must dispatch it to a background thread.
 */
@Component
public class MiseCli {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

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

    public Result run(List<String> args) {
        return run(args, DEFAULT_TIMEOUT);
    }

    public Result run(List<String> args, Duration timeout) {
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

            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(-1, "", "Timed out after " + timeout.getSeconds() + "s: mise " + String.join(" ", args));
            }

            return new Result(process.exitValue(), stdoutFuture.join(), stderrFuture.join());
        } catch (IOException e) {
            return new Result(-1, "", "Failed to run mise: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-1, "", "Interrupted while running mise " + String.join(" ", args));
        }
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
