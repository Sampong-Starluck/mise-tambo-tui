package com.sampong.tambo.mise.implement;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.sampong.tambo.mise.MiseCli;
import com.sampong.tambo.mise.MiseMaintenanceService;

/**
 * Maintenance of the mise installation itself: the full {@code mise doctor}
 * report and {@code mise self-update}. Both stream their output line-by-line
 * so the UI can show a live log while they run.
 */
@Service
public class MiseMaintenanceServiceImp implements MiseMaintenanceService {

    private final MiseCli cli;

    public MiseMaintenanceServiceImp(MiseCli cli) {
        this.cli = cli;
    }

    @Override
    public MiseCli.Result doctor(Consumer<String> onLine) {
        return cli.runStreaming(List.of("doctor"), Duration.ofSeconds(30), onLine);
    }

    @Override
    public MiseCli.Result selfUpdate(Consumer<String> onLine) {
        return cli.runStreaming(List.of("self-update", "-y"), Duration.ofMinutes(5), onLine);
    }

    @Override
    public MiseCli.Result trust() {
        return cli.run(List.of("trust"), Duration.ofSeconds(15));
    }
}
