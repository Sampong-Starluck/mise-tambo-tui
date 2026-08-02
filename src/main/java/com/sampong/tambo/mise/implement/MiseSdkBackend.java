package com.sampong.tambo.mise.implement;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo.mise.MiseQueryService;
import com.sampong.tambo.mise.MiseToolService;
import com.sampong.tambo.mise.model.RegistryEntry;
import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo._common.service.SdkVersionBackend;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Adapts the existing mise services to {@link SdkVersionBackend} — a thin, no-op-logic delegate. */
@Service
@RequiredArgsConstructor
public class MiseSdkBackend implements SdkVersionBackend {

    @NonNull
    private final MiseQueryService query;
    @NonNull
    private final MiseToolService tools;

    @Override
    public String name() {
        return "mise";
    }

    @Override
    public List<ToolVersion> listTools() {
        return query.listTools();
    }

    @Override
    public List<String> listRemoteVersions(String tool) {
        return query.listRemoteVersions(tool);
    }

    @Override
    public List<RegistryEntry> listAvailable() {
        return query.listRegistry();
    }

    @Override
    public CliResult install(String toolAtVersion, Consumer<String> onLine, String cancelKey) {
        return tools.install(toolAtVersion, onLine, cancelKey);
    }

    @Override
    public CliResult uninstall(String toolAtVersion) {
        return tools.uninstall(toolAtVersion);
    }

    @Override
    public CliResult remove(String toolAtVersion) {
        return tools.remove(toolAtVersion);
    }

    @Override
    public CliResult use(String toolAtVersion, boolean global, Consumer<String> onLine, String cancelKey) {
        return tools.use(toolAtVersion, global, onLine, cancelKey);
    }
}
