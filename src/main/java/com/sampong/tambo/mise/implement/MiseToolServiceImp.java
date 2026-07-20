package com.sampong.tambo.mise.implement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.sampong.tambo.mise.MiseCli;
import com.sampong.tambo.mise.MiseToolService;

/**
 * Mutating tool operations: install, uninstall, {@code use} (write into
 * {@code mise.toml}), and running tasks. Long-running operations stream their
 * output line-by-line through the supplied consumer so the UI can show a live log.
 */
@Service
public class MiseToolServiceImp implements MiseToolService {

    private final MiseCli cli;

    public MiseToolServiceImp(MiseCli cli) {
        this.cli = cli;
    }

    @Override
    public MiseCli.Result install(String toolAtVersion, Consumer<String> onLine) {
        return cli.runStreaming(List.of("install", toolAtVersion), Duration.ofMinutes(10), onLine);
    }

    @Override
    public MiseCli.Result uninstall(String toolAtVersion) {
        return cli.run(List.of("uninstall", toolAtVersion), Duration.ofMinutes(2));
    }

    @Override
    public MiseCli.Result use(String toolAtVersion, boolean global, Consumer<String> onLine) {
        List<String> args = new ArrayList<>();
        args.add("use");
        if (global) {
            args.add("-g");
        }
        args.add(toolAtVersion);
        return cli.runStreaming(args, Duration.ofMinutes(10), onLine);
    }

    @Override
    public MiseCli.Result runTask(String taskName, Consumer<String> onLine) {
        return cli.runStreaming(List.of("run", taskName), Duration.ofMinutes(15), onLine);
    }
}
