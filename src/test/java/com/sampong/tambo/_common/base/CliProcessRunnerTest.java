package com.sampong.tambo._common.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo.testsupport.FakeCli;
import com.sampong.tambo.testsupport.Fixtures;

/** Drives the runner against {@link FakeCli}, a real subprocess, so pipes and kills are genuine. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CliProcessRunnerTest {

    private static final Duration GENEROUS = Duration.ofSeconds(30);

    private final AsyncTaskExecutor executor = new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    private final CancelRegistry registry = new CancelRegistry();
    private final CliProcessRunner runner = new CliProcessRunner(FakeCli.javaBinary(), executor, registry);

    @Test
    void capturesStdoutStderrAndExitCode() {
        CliResult result = runner.run(FakeCli.args("exit", "3", "on stdout", "on stderr"), GENEROUS);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).isEqualTo("on stdout");
        assertThat(result.stderr()).isEqualTo("on stderr");
    }

    @Test
    void aMissingBinaryIsAFailedResultNotAnException() {
        CliProcessRunner missing = new CliProcessRunner("tambo-no-such-binary-xyz", executor, registry);

        CliResult result = missing.run(List.of("ls"), GENEROUS);

        assertThat(result.ok()).isFalse();
        assertThat(result.stderr()).startsWith("Failed to launch tambo-no-such-binary-xyz");
    }

    @Test
    void aHungCommandTimesOutAndIsKilled() {
        long start = System.nanoTime();

        CliResult result = runner.run(FakeCli.args("sleep", "60000"), Duration.ofSeconds(3));

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.stderr()).startsWith("Timed out after 3s");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
    }

    /**
     * The captured vfox install, replayed through a real pipe: every {@code \r} redraw must
     * arrive as its own ANSI-free line, which is what the progress parsers rely on.
     */
    @Test
    void streamingSplitsCarriageReturnRedrawsIntoLines() {
        List<String> lines = new CopyOnWriteArrayList<>();
        String fixture = Fixtures.path("vfox-install-nodejs.txt").toString();

        CliResult result = runner.runStreaming(FakeCli.args("cat", fixture), GENEROUS, lines::add, null);

        assertThat(result.ok()).isTrue();
        assertThat(lines).isEqualTo(Fixtures.streamedLines("vfox-install-nodejs.txt"));
        assertThat(lines).noneMatch(l -> l.contains("\u001B") || l.contains("\r"));
        assertThat(lines.stream().filter(l -> l.startsWith("Downloading...")).count()).isGreaterThan(90);
        assertThat(result.stdout()).contains("Install nodejs@22.11.0 success!");
    }

    @Test
    void aStreamingCommandCanBeCancelledByKey() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        CompletableFuture<CliResult> run = CompletableFuture.supplyAsync(() ->
                runner.runStreaming(FakeCli.args("sleep", "60000"), GENEROUS, lines::add, "task:dev"), executor);
        awaitLine(lines, "started");

        assertThat(registry.isRunning("task:dev")).isTrue();
        assertThat(registry.cancel("task:dev")).isTrue();

        CliResult result = run.get(20, TimeUnit.SECONDS);
        assertThat(result.ok()).isFalse();
        assertThat(registry.isRunning("task:dev")).isFalse();
    }

    /** The quit-while-busy fix: a plain {@code run} has no cancel key but is still killed on exit. */
    @Test
    void anUnkeyedCommandIsKilledWhenTheRegistryShutsDown() throws Exception {
        CompletableFuture<CliResult> run = CompletableFuture.supplyAsync(() ->
                runner.run(FakeCli.args("sleep", "60000"), GENEROUS), executor);
        Thread.sleep(1500); // let the JVM start; destroy() before launch would find nothing to kill

        registry.destroy();

        CliResult result = run.get(20, TimeUnit.SECONDS);
        assertThat(result.ok()).isFalse();
    }

    private static void awaitLine(List<String> lines, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!lines.contains(expected)) {
            assertThat(System.nanoTime()).as("waiting for '" + expected + "'").isLessThan(deadline);
            Thread.sleep(20);
        }
    }
}
