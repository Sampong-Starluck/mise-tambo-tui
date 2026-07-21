package com.sampong.tambo.mise.implement;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.sampong.tambo.mise.MiseCli;
import com.sampong.tambo.mise.MiseMaintenanceService;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Maintenance of the mise installation itself: the full {@code mise doctor}
 * report and {@code mise self-update}. Both stream their output line-by-line
 * so the UI can show a live log while they run.
 */
@Service
@RequiredArgsConstructor
public class MiseMaintenanceServiceImp implements MiseMaintenanceService {

    @NonNull
    private final MiseCli cli;

    @Override
    public MiseCli.Result doctor(@NonNull Consumer<String> onLine) {
        return cli.runStreaming(List.of("doctor"), Duration.ofSeconds(30), onLine);
    }

    @Override
    public MiseCli.Result selfUpdate(@NonNull Consumer<String> onLine) {
        return cli.runStreaming(List.of("self-update", "-y"), Duration.ofMinutes(5), onLine);
    }

    @Override
    public MiseCli.Result trust() {
        return cli.run(List.of("trust"), Duration.ofSeconds(15));
    }

    @Override
    public MiseCli.Result validateConfig() {
        // `config get` (no key) loads and parses every active config file, so a
        // TOML syntax error or bad table fails here with a descriptive message.
        return cli.run(List.of("config", "get"), Duration.ofSeconds(15));
    }

    @Override
    public MiseCli.Result prune(@NonNull Consumer<String> onLine) {
        return cli.runStreaming(List.of("prune"), Duration.ofMinutes(5), onLine);
    }
}
