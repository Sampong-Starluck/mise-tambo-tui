package com.sampong.tambo.mise;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

/**
 * Mutating tool operations: install, uninstall, {@code use} (write into
 * {@code mise.toml}), and running tasks. Long-running operations stream their
 * output line-by-line through the supplied consumer so the UI can show a live log.
 */
@Service
public class MiseToolService {

    private final MiseCli cli;

    public MiseToolService(MiseCli cli) {
        this.cli = cli;
    }

    public MiseCli.Result install(String toolAtVersion, Consumer<String> onLine) {
        return cli.runStreaming(List.of("install", toolAtVersion), Duration.ofMinutes(10), onLine);
    }

    public MiseCli.Result uninstall(String toolAtVersion) {
        return cli.run(List.of("uninstall", toolAtVersion), Duration.ofMinutes(2));
    }

    /**
     * Runs {@code mise use [-g] tool@version}: installs the tool if needed and pins
     * it in the project's {@code ./mise.toml} (or the global config with {@code -g}).
     */
    public MiseCli.Result use(String toolAtVersion, boolean global, Consumer<String> onLine) {
        List<String> args = new ArrayList<>();
        args.add("use");
        if (global) {
            args.add("-g");
        }
        args.add(toolAtVersion);
        return cli.runStreaming(args, Duration.ofMinutes(10), onLine);
    }

    public MiseCli.Result runTask(String taskName, Consumer<String> onLine) {
        return cli.runStreaming(List.of("run", taskName), Duration.ofMinutes(15), onLine);
    }
}
