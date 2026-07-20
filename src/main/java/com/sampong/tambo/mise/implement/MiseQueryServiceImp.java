package com.sampong.tambo.mise.implement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sampong.tambo.mise.MiseCli;
import com.sampong.tambo.mise.MiseQueryService;
import com.sampong.tambo.mise.model.DoctorInfo;
import com.sampong.tambo.mise.model.MiseTask;
import com.sampong.tambo.mise.model.RegistryEntry;
import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo.mise.model.TrustStatus;

/**
 * Read-only queries against {@code mise}: turns raw CLI output (mostly {@code -J}
 * JSON) into typed data the TUI can render. Never mutates any mise state —
 * mutating operations live in {@link com.sampong.tambo.mise.MiseToolService} and
 * {@link com.sampong.tambo.mise.MiseMaintenanceService}.
 */
@Service
// Native image: these types are only ever reached through Jackson reflection,
// so Spring AOT must be told to keep their constructors/accessors.
@RegisterReflectionForBinding({MiseTask.class, RegistryEntry.class,
        MiseQueryServiceImp.RawVersion.class, MiseQueryServiceImp.RawSource.class})
public class MiseQueryServiceImp implements MiseQueryService {

    private final MiseCli cli;
    private final ObjectMapper mapper;

    public MiseQueryServiceImp(MiseCli cli, ObjectMapper mapper) {
        this.cli = cli;
        this.mapper = mapper;
    }

    @Override
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

    @Override
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

    @Override
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

    @Override
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

    @Override
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

    @Override
    public DoctorInfo doctorSummary() {
        MiseCli.Result result = cli.run(List.of("doctor"));
        String version = "unknown";
        // `mise activate` exports MISE_SHELL / __MISE_DIFF into the launching
        // shell, and this process inherits them — the most reliable signal on
        // Windows, where doctor prints no "activated:" line at all.
        boolean activated = activatedFromEnvironment();
        boolean shimsOnPath = false;
        int configFiles = 0;
        if (result.ok() || !result.stdout().isBlank()) {
            boolean inConfigFiles = false;
            boolean inShell = false;
            for (String rawLine : result.stdout().split("\n")) {
                String line = rawLine.strip();
                if (inShell) {
                    // First indented line under "shell:" — "(unknown)" means the
                    // launching shell never ran `mise activate`.
                    inShell = false;
                    if (rawLine.startsWith(" ") && !line.isBlank() && !line.startsWith("(unknown")) {
                        activated = true;
                    }
                }
                if (line.startsWith("version:")) {
                    version = line.substring("version:".length()).strip();
                } else if (line.startsWith("activated:")) {
                    activated = activated
                            || line.substring("activated:".length()).strip().equalsIgnoreCase("yes");
                } else if (line.startsWith("MISE_SHELL=")) {
                    activated = true;
                } else if (line.equals("shell:")) {
                    inShell = true;
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

    private static boolean activatedFromEnvironment() {
        for (String var : new String[]{"MISE_SHELL", "__MISE_DIFF", "__MISE_SESSION"}) {
            String value = System.getenv(var);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<TrustStatus> trustStatus() {
        MiseCli.Result result = cli.run(List.of("trust", "--show"));
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        List<TrustStatus> statuses = new ArrayList<>();
        for (String line : result.stdout().split("\n")) {
            // "<path>: trusted|untrusted" — split at the LAST colon, since Windows
            // paths contain one of their own ("C:\...").
            int sep = line.lastIndexOf(':');
            if (sep < 0) {
                continue;
            }
            String path = line.substring(0, sep).strip();
            String status = line.substring(sep + 1).strip().toLowerCase();
            if (path.isEmpty()) {
                continue;
            }
            switch (status) {
                case "trusted" -> statuses.add(new TrustStatus(path, true));
                case "untrusted", "ignored" -> statuses.add(new TrustStatus(path, false));
                default -> {
                    // not a trust line (e.g. a warning) — skip it
                }
            }
        }
        return statuses;
    }

    // ==================== JSON DTOs ====================

    // Package-private (not private): ECJ rejects private nested types referenced
    // from the @RegisterReflectionForBinding annotation on the outer class.
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class RawVersion {
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
    static final class RawSource {
        public String type;
        public String path;
    }
}
