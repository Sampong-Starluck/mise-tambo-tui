package com.sampong.tambo.mise;

import java.util.function.Consumer;

/**
 * Mutating tool operations: install, uninstall, {@code use} (write into
 * {@code mise.toml}), and running tasks. Long-running operations stream their
 * output line-by-line through the supplied consumer so the UI can show a live log.
 */
public interface MiseToolService {

    MiseCli.Result install(String toolAtVersion, Consumer<String> onLine);

    MiseCli.Result uninstall(String toolAtVersion);

    /**
     * Runs {@code mise use [-g] tool@version}: installs the tool if needed and pins
     * it in the project's {@code ./mise.toml} (or the global config with {@code -g}).
     */
    MiseCli.Result use(String toolAtVersion, boolean global, Consumer<String> onLine);

    MiseCli.Result runTask(String taskName, Consumer<String> onLine);
}
