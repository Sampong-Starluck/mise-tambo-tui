package com.sampong.tambo.mise;

import java.util.function.Consumer;

import com.sampong.tambo._common.model.CliResult;

/**
 * Mutating tool operations: install, uninstall, {@code use} (write into
 * {@code mise.toml}), and running tasks. Long-running operations stream their
 * output line-by-line through the supplied consumer so the UI can show a live log.
 */
public interface MiseToolService {

    /** Streaming ops take a {@code cancelKey} they register under so the UI can abort them. */
    CliResult install(String toolAtVersion, Consumer<String> onLine, String cancelKey);

    /**
     * Runs bare {@code mise install}: installs every tool the active config files declare that
     * is not on disk yet, and nothing else. Unlike {@link #use} it never rewrites
     * {@code mise.toml} — the versions it installs are the ones already written there.
     */
    CliResult installAll(Consumer<String> onLine, String cancelKey);

    CliResult uninstall(String toolAtVersion);

    /**
     * Runs {@code mise unuse tool[@version]}: removes the entry from whichever
     * config file declares it (project {@code mise.toml} or global) and, unless
     * another config still references it, prunes the installed version too.
     */
    CliResult remove(String toolAtVersion);

    /**
     * Runs {@code mise plugins uninstall <plugin>}. Installed versions are kept (no
     * {@code --purge}). Only external plugins can be removed — core and registry-backed tools
     * have no plugin, which surfaces as a failed result rather than mise's silent exit 0.
     */
    CliResult removePlugin(String plugin);

    /**
     * Runs {@code mise upgrade <tool>} to install and switch to the newest version
     * allowed by the config. Pass a bare tool name to upgrade just that tool, or an
     * empty string to upgrade every outdated tool. Streams output line-by-line.
     */
    CliResult upgrade(String tool, Consumer<String> onLine, String cancelKey);

    /**
     * Runs {@code mise use [-g] tool@version}: installs the tool if needed and pins
     * it in the project's {@code ./mise.toml} (or the global config with {@code -g}).
     */
    CliResult use(String toolAtVersion, boolean global, Consumer<String> onLine, String cancelKey);

    /**
     * The same {@code mise use [-g] tool@version} as {@link #use}, for a version already on
     * disk: nothing is downloaded, so it runs unstreamed and on a short timeout rather than
     * reserving the ten-minute install budget for what is a config rewrite.
     */
    CliResult pin(String toolAtVersion, boolean global);

    /**
     * Runs {@code mise run <task>}, appending {@code -- <args>} when {@code args}
     * is non-blank so the extra tokens are forwarded to the task.
     */
    CliResult runTask(String taskName, String args, Consumer<String> onLine, String cancelKey);
}
