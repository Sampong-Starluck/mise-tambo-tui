package com.sampong.tambo.mise;

import java.util.function.Consumer;

import com.sampong.tambo.cli.CliResult;

/**
 * Maintenance of the mise installation itself: the full {@code mise doctor}
 * report and {@code mise self-update}. Both stream their output line-by-line
 * so the UI can show a live log while they run.
 */
public interface MiseMaintenanceService {

    /** Runs the full {@code mise doctor} report. Exit code is non-zero when problems were found. */
    CliResult doctor(Consumer<String> onLine);

    /**
     * Runs {@code mise self-update -y} (no confirmation prompt — the CLI would
     * otherwise hang waiting on stdin, which is closed). Package-manager installs
     * (scoop, brew, …) refuse self-update; the error line explains that.
     */
    CliResult selfUpdate(Consumer<String> onLine);

    /**
     * Runs {@code mise trust}: marks the config file in the working directory
     * (or the nearest parent) as trusted so mise is allowed to parse it.
     */
    CliResult trust();

    /**
     * Forces mise to parse the active config (via {@code mise config get}) so a
     * broken edit surfaces immediately. A non-zero result carries the parse error.
     */
    CliResult validateConfig();

    /** Runs {@code mise prune} to delete unused/old tool versions, streaming progress. */
    CliResult prune(Consumer<String> onLine);
}
