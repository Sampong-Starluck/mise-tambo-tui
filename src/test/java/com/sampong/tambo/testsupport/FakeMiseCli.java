package com.sampong.tambo.testsupport;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.springframework.core.task.support.TaskExecutorAdapter;

import com.sampong.tambo._common.base.CancelRegistry;
import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo.cli.TamboCommand;
import com.sampong.tambo.mise.MiseCli;

import picocli.CommandLine;

/** A {@link MiseCli} that answers from {@link CannedResponses} instead of running {@code mise}. */
public final class FakeMiseCli extends MiseCli {

    private final CannedResponses canned;

    private FakeMiseCli(TamboCommand command, CannedResponses canned) {
        super(new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()), new CancelRegistry(), command);
        this.canned = canned;
    }

    /** A fake as if tambo were launched with {@code launchArgs}, e.g. {@code --offline}. */
    public static FakeMiseCli launchedWith(CannedResponses canned, String... launchArgs) {
        TamboCommand command = new TamboCommand();
        new CommandLine(command).parseArgs(launchArgs);
        return new FakeMiseCli(command, canned);
    }

    @Override
    public CliResult run(List<String> args) {
        return canned.answer(args);
    }

    @Override
    public CliResult run(List<String> args, Duration timeout) {
        return canned.answer(args);
    }

    @Override
    public CliResult runStreaming(List<String> args, Duration timeout, Consumer<String> onLine,
                                  @Nullable String cancelKey) {
        return canned.stream(args, onLine);
    }
}
