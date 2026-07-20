package com.sampong.tambo.mise;

import java.util.function.Consumer;

/**
 * Maintenance of the mise installation itself: the full {@code mise doctor}
 * report and {@code mise self-update}. Both stream their output line-by-line
 * so the UI can show a live log while they run.
 */
public interface MiseMaintenanceService {

    /** Runs the full {@code mise doctor} report. Exit code is non-zero when problems were found. */
    MiseCli.Result doctor(Consumer<String> onLine);

    /**
     * Runs {@code mise self-update -y} (no confirmation prompt — the CLI would
     * otherwise hang waiting on stdin, which is closed). Package-manager installs
     * (scoop, brew, …) refuse self-update; the error line explains that.
     */
    MiseCli.Result selfUpdate(Consumer<String> onLine);

    /**
     * Runs {@code mise trust}: marks the config file in the working directory
     * (or the nearest parent) as trusted so mise is allowed to parse it.
     */
    MiseCli.Result trust();
}
