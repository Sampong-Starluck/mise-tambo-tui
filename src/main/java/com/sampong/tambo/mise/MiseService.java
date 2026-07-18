package com.sampong.tambo.mise;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * Higher-level facade over {@link MiseCli}: turns raw {@code mise} CLI output into
 * typed data the TUI can render, and exposes the mutating operations (install,
 * uninstall, run task, use) as simple blocking calls the caller offloads to a
 * background thread.
 */
@Service
public class MiseService {

    private final MiseCli cli;
    private final ObjectMapper mapper;

    public MiseService(MiseCli cli, ObjectMapper mapper) {
        this.cli = cli;
        this.mapper = mapper;
    }

    // ==================== Read-only state ====================

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
            tools.sort(Comparator.comparing(ToolVersion::tool).thenComparing(ToolVersion::version));
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
            List<MiseTask> tasks = mapper.readValue(result.stdout(), new TypeReference<List<MiseTask>>() {
            });
            return tasks;
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

    public DoctorInfo doctor() {
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

    // ==================== Mutating operations ====================

    public MiseCli.Result install(String toolAtVersion) {
        return cli.run(List.of("install", toolAtVersion), Duration.ofMinutes(10));
    }

    public MiseCli.Result uninstall(String toolAtVersion) {
        return cli.run(List.of("uninstall", toolAtVersion), Duration.ofMinutes(2));
    }

    public MiseCli.Result use(String toolAtVersion, boolean global) {
        List<String> args = new ArrayList<>();
        args.add("use");
        if (global) {
            args.add("-g");
        }
        args.add(toolAtVersion);
        return cli.run(args, Duration.ofMinutes(10));
    }

    public MiseCli.Result runTask(String taskName) {
        return cli.run(List.of("run", taskName), Duration.ofMinutes(15));
    }

    // ==================== Shell activation ====================

    /** The result of installing mise activation into the user's shell rc file. */
    public record ActivationOutcome(boolean ok, boolean changed, String message) {
    }

    /**
     * Enables mise activation for future shells by appending the appropriate
     * {@code mise activate} line to the user's shell rc file (detected from
     * {@code $SHELL}; bash, zsh, and fish are recognized, anything else falls
     * back to bash). Idempotent: does nothing when the rc file already
     * mentions {@code mise activate}.
     */
    public ActivationOutcome activateInShell() {
        String home = System.getProperty("user.home");
        String shellEnv = System.getenv("SHELL");
        String shell = (shellEnv == null || shellEnv.isBlank())
                ? "bash"
                : Path.of(shellEnv).getFileName().toString();

        Path rc;
        String line;
        switch (shell) {
            case "zsh" -> {
                rc = Path.of(home, ".zshrc");
                line = "eval \"$(mise activate zsh)\"";
            }
            case "fish" -> {
                rc = Path.of(home, ".config", "fish", "config.fish");
                line = "mise activate fish | source";
            }
            default -> {
                shell = "bash";
                rc = Path.of(home, ".bashrc");
                line = "eval \"$(mise activate bash)\"";
            }
        }

        try {
            if (Files.exists(rc) && Files.readString(rc).contains("mise activate")) {
                return new ActivationOutcome(true, false,
                        "mise activation already present in " + rc + " — restart your shell if it isn't active yet");
            }
            if (rc.getParent() != null) {
                Files.createDirectories(rc.getParent());
            }
            Files.writeString(rc, "\n# Added by tambo — activate mise\n" + line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return new ActivationOutcome(true, true,
                    "Added \"" + line + "\" to " + rc + " — restart your " + shell + " shell to finish");
        } catch (IOException e) {
            return new ActivationOutcome(false, false, "Could not update " + rc + ": " + e.getMessage());
        }
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
