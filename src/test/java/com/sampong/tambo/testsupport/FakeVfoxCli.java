package com.sampong.tambo.testsupport;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.springframework.core.task.support.TaskExecutorAdapter;

import com.sampong.tambo._common.base.CancelRegistry;
import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo.vfox.VfoxCli;

/** A {@link VfoxCli} that answers from {@link CannedResponses} instead of running {@code vfox}. */
public final class FakeVfoxCli extends VfoxCli {

    private final CannedResponses canned;

    public FakeVfoxCli(CannedResponses canned) {
        super(new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()), new CancelRegistry());
        this.canned = canned;
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
