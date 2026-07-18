package com.sampong.tambo.mise;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sampong.tambo.mise.model.DoctorInfo;
import com.sampong.tambo.mise.model.MiseTask;
import com.sampong.tambo.mise.model.RegistryEntry;
import com.sampong.tambo.mise.model.ToolVersion;

/**
 * Read-only queries against {@code mise}: turns raw CLI output (mostly {@code -J}
 * JSON) into typed data the TUI can render. Never mutates any mise state —
 * mutating operations live in {@link MiseToolService} and
 * {@link MiseMaintenanceService}.
 */
@Service
public class MiseQueryService {

    private final MiseCli cli;
    private final ObjectMapper mapper;

    public MiseQueryService(MiseCli cli, ObjectMapper mapper) {
        this.cli = cli;
        this.mapper = mapper;
    }

    public List<ToolVersion> listTools() {
        MiseCli.Result result = cli.run(List.of("ls", "-J"));
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        try {
            Map<String, List<RawVersion>> raw = mapper.readValue(
                    result.stdout(), new TypeReference<Map<String, List<RawVersion>>>() {
                    });
            List<ToolVersion> tools = new ArrayList<>();
            for (Map.Entry<String, List<RawVersion>> entry : raw.entrySet()) {
                for (RawVersion v : entry.getValue()) {
                    tools.add(new ToolVersion(
                            entry.getKey(),
                            v.version,
                            v.requestedVersion,
                            v.installPath,
                            v.source != null ? v.source.type : null,
                            v.source != null ? v.source.path : null,
                            v.installed,
                            v.active));
                }
            }
            tools.sort(Comparator.comparing((ToolVersion t) -> t.tool())
                    .thenComparing((ToolVersion t) -> t.version()));
            return tools;
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<MiseTask> listTasks() {
        MiseCli.Result result = cli.run(List.of("tasks", "ls", "-J"));
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(result.stdout(), new TypeReference<List<MiseTask>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<RegistryEntry> listRegistry() {
        MiseCli.Result result = cli.run(List.of("registry", "-J"), Duration.ofSeconds(30));
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(result.stdout(), new TypeReference<List<RegistryEntry>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<String, String> listEnv() {
        MiseCli.Result result = cli.run(List.of("env", "-J"));
        if (!result.ok() || result.stdout().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> raw = mapper.readValue(result.stdout(), new TypeReference<Map<String, String>>() {
            });
            return new TreeMap<>(raw);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Lists the installable versions of a tool via {@code mise ls-remote <tool>},
     * newest first, always prefixed with the synthetic {@code "latest"} entry.
     */
    public List<String> listRemoteVersions(String tool) {
        List<String> versions = new ArrayList<>();
        versions.add("latest");
        MiseCli.Result result = cli.run(List.of("ls-remote", tool), Duration.ofSeconds(30));
        if (result.ok() && !result.stdout().isBlank()) {
            List<String> parsed = new ArrayList<>();
            for (String line : result.stdout().split("\n")) {
                if (!line.isBlank()) {
                    parsed.add(line.strip());
                }
            }
            Collections.reverse(parsed); // ls-remote lists oldest first
            versions.addAll(parsed);
        }
        return versions;
    }

    /** A compact health summary parsed out of {@code mise doctor} plain-text output. */
    public DoctorInfo doctorSummary() {
        MiseCli.Result result = cli.run(List.of("doctor"));
        String version = "unknown";
        boolean activated = false;
        boolean shimsOnPath = false;
        int configFiles = 0;
        if (result.ok() || !result.stdout().isBlank()) {
            boolean inConfigFiles = false;
            for (String rawLine : result.stdout().split("\n")) {
                String line = rawLine.strip();
                if (line.startsWith("version:")) {
                    version = line.substring("version:".length()).strip();
                } else if (line.startsWith("activated:")) {
                    activated = line.substring("activated:".length()).strip().equalsIgnoreCase("yes");
                } else if (line.startsWith("MISE_SHELL=")) {
                    // Windows doctor output has no "activated:" line; an inherited
                    // MISE_SHELL env var means the launching shell ran `mise activate`.
                    activated = true;
                } else if (line.startsWith("shims_on_path:")) {
                    shimsOnPath = line.substring("shims_on_path:".length()).strip().equalsIgnoreCase("yes");
                } else if (line.startsWith("config_files:")) {
                    inConfigFiles = true;
                } else if (inConfigFiles) {
                    if (rawLine.isBlank() || !rawLine.startsWith(" ")) {
                        inConfigFiles = false;
                    } else {
                        configFiles++;
                    }
                }
            }
        }
        return new DoctorInfo(version, activated, shimsOnPath, configFiles);
    }

    // ==================== JSON DTOs ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RawVersion {
        public String version;
        @JsonProperty("requested_version")
        public String requestedVersion;
        @JsonProperty("install_path")
        public String installPath;
        public RawSource source;
        public boolean installed;
        public boolean active;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RawSource {
        public String type;
        public String path;
    }
}
