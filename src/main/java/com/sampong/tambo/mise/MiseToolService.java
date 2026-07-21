package com.sampong.tambo.mise;

import java.util.function.Consumer;

/**
 * Mutating tool operations: install, uninstall, {@code use} (write into
 * {@code mise.toml}), and running tasks. Long-running operations stream their
 * output line-by-line through the supplied consumer so the UI can show a live log.
 */
public interface MiseToolService {

    /** Streaming ops take a {@code cancelKey} they register under so the UI can abort them. */
    MiseCli.Result install(String toolAtVersion, Consumer<String> onLine, String cancelKey);

    MiseCli.Result uninstall(String toolAtVersion);

    /**
     * Runs {@code mise upgrade <tool>} to install and switch to the newest version
     * allowed by the config. Pass a bare tool name to upgrade just that tool, or an
     * empty string to upgrade every outdated tool. Streams output line-by-line.
     */
    MiseCli.Result upgrade(String tool, Consumer<String> onLine, String cancelKey);

    /**
     * Runs {@code mise use [-g] tool@version}: installs the tool if needed and pins
     * it in the project's {@code ./mise.toml} (or the global config with {@code -g}).
     */
    MiseCli.Result use(String toolAtVersion, boolean global, Consumer<String> onLine, String cancelKey);

    /**
     * Runs {@code mise run <task>}, appending {@code -- <args>} when {@code args}
     * is non-blank so the extra tokens are forwarded to the task.
     */
    MiseCli.Result runTask(String taskName, String args, Consumer<String> onLine, String cancelKey);
}
