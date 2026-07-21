package com.sampong.tambo.mise;

import java.util.List;
import java.util.Map;

import com.sampong.tambo.mise.model.DoctorInfo;
import com.sampong.tambo.mise.model.MiseTask;
import com.sampong.tambo.mise.model.OutdatedTool;
import com.sampong.tambo.mise.model.RegistryEntry;
import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo.mise.model.TrustStatus;

/**
 * Read-only queries against {@code mise}: turns raw CLI output (mostly {@code -J}
 * JSON) into typed data the TUI can render. Never mutates any mise state —
 * mutating operations live in {@link MiseToolService} and
 * {@link MiseMaintenanceService}.
 */
public interface MiseQueryService {

    List<ToolVersion> listTools();

    /**
     * Tools with a newer version available, via {@code mise outdated -J}. Empty
     * when everything is current or the command is unavailable.
     */
    List<OutdatedTool> listOutdated();

    List<MiseTask> listTasks();

    List<RegistryEntry> listRegistry();

    Map<String, String> listEnv();

    /**
     * Lists the installable versions of a tool via {@code mise ls-remote <tool>},
     * newest first, always prefixed with the synthetic {@code "latest"} entry.
     */
    List<String> listRemoteVersions(String tool);

    /** A compact health summary parsed out of {@code mise doctor} plain-text output. */
    DoctorInfo doctorSummary();

    /**
     * The trust state of every config directory {@code mise trust --show} reports
     * for the working directory and its parents. Empty when mise is unavailable
     * or the output could not be parsed.
     */
    List<TrustStatus> trustStatus();
}
