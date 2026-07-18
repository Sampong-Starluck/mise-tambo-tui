package com.sampong.tambo.mise;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

/**
 * Maintenance of the mise installation itself: the full {@code mise doctor}
 * report and {@code mise self-update}. Both stream their output line-by-line
 * so the UI can show a live log while they run.
 */
@Service
public class MiseMaintenanceService {

    private final MiseCli cli;

    public MiseMaintenanceService(MiseCli cli) {
        this.cli = cli;
    }

    /** Runs the full {@code mise doctor} report. Exit code is non-zero when problems were found. */
    public MiseCli.Result doctor(Consumer<String> onLine) {
        return cli.runStreaming(List.of("doctor"), Duration.ofSeconds(30), onLine);
    }

    /**
     * Runs {@code mise self-update -y} (no confirmation prompt — the CLI would
     * otherwise hang waiting on stdin, which is closed). Package-manager installs
     * (scoop, brew, …) refuse self-update; the error line explains that.
     */
    public MiseCli.Result selfUpdate(Consumer<String> onLine) {
        return cli.runStreaming(List.of("self-update", "-y"), Duration.ofMinutes(5), onLine);
    }
}
