package com.sampong.tambo.mise.implement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.sampong.tambo.mise.MiseCli;
import com.sampong.tambo.mise.MiseToolService;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Mutating tool operations: install, uninstall, {@code use} (write into
 * {@code mise.toml}), and running tasks. Long-running operations stream their
 * output line-by-line through the supplied consumer so the UI can show a live log.
 */
@Service
@RequiredArgsConstructor
public class MiseToolServiceImp implements MiseToolService {

    @NonNull
    private final MiseCli cli;

    @Override
    public MiseCli.Result install(@NonNull String toolAtVersion, @NonNull Consumer<String> onLine,
                                  @NonNull String cancelKey) {
        return cli.runStreaming(List.of("install", toolAtVersion), Duration.ofMinutes(10), onLine, cancelKey);
    }

    @Override
    public MiseCli.Result uninstall(@NonNull String toolAtVersion) {
        return cli.run(List.of("uninstall", toolAtVersion), Duration.ofMinutes(2));
    }

    @Override
    public MiseCli.Result upgrade(@NonNull String tool, @NonNull Consumer<String> onLine,
                                  @NonNull String cancelKey) {
        List<String> args = new ArrayList<>();
        args.add("upgrade");
        if (!tool.isBlank()) {
            args.add(tool);
        }
        return cli.runStreaming(args, Duration.ofMinutes(10), onLine, cancelKey);
    }

    @Override
    public MiseCli.Result use(@NonNull String toolAtVersion, boolean global, @NonNull Consumer<String> onLine,
                              @NonNull String cancelKey) {
        List<String> args = new ArrayList<>();
        args.add("use");
        if (global) {
            args.add("-g");
        }
        args.add(toolAtVersion);
        return cli.runStreaming(args, Duration.ofMinutes(10), onLine, cancelKey);
    }

    @Override
    public MiseCli.Result runTask(@NonNull String taskName, @NonNull String args, @NonNull Consumer<String> onLine,
                                  @NonNull String cancelKey) {
        List<String> command = new ArrayList<>();
        command.add("run");
        command.add(taskName);
        if (!args.isBlank()) {
            command.add("--");
            for (String token : args.strip().split("\\s+")) {
                command.add(token);
            }
        }
        return cli.runStreaming(command, Duration.ofMinutes(15), onLine, cancelKey);
    }
}
