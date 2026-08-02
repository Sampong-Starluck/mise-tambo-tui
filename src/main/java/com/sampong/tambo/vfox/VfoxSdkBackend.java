package com.sampong.tambo.vfox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo.mise.model.RegistryEntry;
import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo._common.service.SdkVersionBackend;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Implements the install/use/list slice of {@link SdkVersionBackend} against the {@code vfox}
 * CLI. Unlike mise, vfox has no JSON output for any command, so every method here parses plain
 * text; the parsers are written defensively (unrecognized lines are skipped rather than
 * throwing) since the exact formatting was derived by reading vfox's Go source rather than by
 * running the binary — expect this to need a short calibration pass against a real install.
 */
@Service
@RequiredArgsConstructor
public class VfoxSdkBackend implements SdkVersionBackend {

    /** vfox requires a plugin be registered before a version of it can be installed. */
    private static final Duration ADD_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CURRENT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration USE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration UNINSTALL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration UNUSE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AVAILABLE_TIMEOUT = Duration.ofSeconds(20);
    /** Longer than {@link #ADD_TIMEOUT}: a user-supplied {@code --source} may be a git clone. */
    private static final Duration ADD_PLUGIN_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SELF_UPDATE_TIMEOUT = Duration.ofMinutes(5);

    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[;\\d]*[ -/]*[@-~]");
    /** Leading pterm tree-drawing characters (├─┬ └── │   etc.) plus whitespace. */
    private static final Pattern TREE_PREFIX = Pattern.compile("^[\\s│├└┬─]+");
    /** A bare version token, e.g. "21.5.0" or "v21.5.0" — distinguishes version rows from tool-name rows. */
    private static final Pattern VERSION_LIKE = Pattern.compile("^v?\\d+(\\.\\d+)*([\\-+.].*)?$");

    @NonNull
    private final VfoxCli cli;

    @Override
    public String name() {
        return "vfox";
    }

    @Override
    public List<ToolVersion> listTools() {
        CliResult result = cli.run(List.of("list"), LIST_TIMEOUT);
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        // `vfox list`'s tree marks the active version inline (unconfirmed exact wording), so
        // that's kept as a fallback signal, but `vfox current` is a purpose-built, far more
        // reliable source of truth for which version is active per tool.
        Map<String, String> current = currentVersions();
        List<ToolVersion> tools = new ArrayList<>();
        String currentTool = null;
        for (String rawLine : result.stdout().split("\n")) {
            String line = clean(rawLine);
            if (line.isEmpty() || line.equalsIgnoreCase("All installed sdk versions")) {
                continue;
            }
            boolean markedActive = line.toLowerCase().contains("current");
            String token = line.replaceAll("(?i)<[—-]+\\s*current.*$", "").strip();
            if (VERSION_LIKE.matcher(token).matches()) {
                if (currentTool != null) {
                    String version = token.startsWith("v") ? token.substring(1) : token;
                    boolean active = version.equals(current.get(currentTool)) || markedActive;
                    tools.add(new ToolVersion(currentTool, version, null, null, null, null, true, active));
                }
            } else {
                currentTool = token.toLowerCase();
            }
        }
        return tools;
    }

    /** {@code vfox current} (no args): the active version of every installed SDK, tool name -> version. */
    private Map<String, String> currentVersions() {
        CliResult result = cli.run(List.of("current"), CURRENT_TIMEOUT);
        if (!result.ok() || result.stdout().isBlank()) {
            return Map.of();
        }
        Map<String, String> current = new HashMap<>();
        for (String rawLine : result.stdout().split("\n")) {
            String line = clean(rawLine);
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue; // header or a "not set" line rather than a "<tool> <version>" row
            }
            String tool = parts[0].toLowerCase();
            String version = parts[parts.length - 1];
            current.put(tool, version.startsWith("v") ? version.substring(1) : version);
        }
        return current;
    }

    @Override
    public List<String> listRemoteVersions(String tool) {
        // Plain `vfox search <tool>` only returns a short recent-versions page; "all" is
        // required to get the full list the fuzzy-find version step is meant to browse.
        CliResult result = cli.run(List.of("search", tool, "all"), SEARCH_TIMEOUT);
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        List<String> versions = new ArrayList<>();
        for (String rawLine : result.stdout().split("\n")) {
            String line = clean(rawLine);
            if (!line.startsWith("-")) {
                continue; // header ("Available versions:") or anything unexpected
            }
            // Each row is "- <version> [<trailing annotations>]", e.g.
            // "24.15.0 (LTS) [npm 11.12.1] (installed)" for nodejs — everything from the
            // first space on is decoration, not part of the version token the CLI expects.
            String rest = line.substring(1).strip();
            int spaceIndex = rest.indexOf(' ');
            String version = spaceIndex < 0 ? rest : rest.substring(0, spaceIndex);
            if (!version.isEmpty()) {
                versions.add(version);
            }
        }
        return versions;
    }

    /**
     * {@code vfox available} prints a header ("AVAILABLE PLUGINS"), one
     * {@code  <name> <✓/✗> <homepage>} row per plugin, and a footer hint line — none of it
     * JSON. The ✓/✗ column marks whether the plugin is in vfox's official registry.
     */
    @Override
    public List<RegistryEntry> listAvailable() {
        CliResult result = cli.run(List.of("available"), AVAILABLE_TIMEOUT);
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        List<RegistryEntry> entries = new ArrayList<>();
        for (String rawLine : result.stdout().split("\n")) {
            String line = clean(rawLine);
            if (line.isEmpty() || line.equalsIgnoreCase("AVAILABLE PLUGINS")
                    || line.toLowerCase().startsWith("use ")) {
                continue; // header or the "Use 'vfox add <plugin>' to install" footer
            }
            String[] parts = line.split("\\s+", 3);
            if (parts.length < 2) {
                continue;
            }
            String name = parts[0];
            boolean official = parts[1].contains("✓");
            String homepage = parts.length > 2 ? parts[2].strip() : "";
            String description = (official ? "official" : "community") + (homepage.isEmpty() ? "" : " — " + homepage);
            entries.add(new RegistryEntry(name, null, description, null));
        }
        return entries;
    }

    /**
     * Registers a plugin standalone, without installing any version — {@code args} is passed
     * straight through to {@code vfox add} (e.g. {@code ["nodejs"]} or
     * {@code ["myplugin", "--alias", "foo", "--source", "https://…"]}), mirroring vfox's own
     * CLI syntax exactly rather than parsing flags ourselves. Not part of
     * {@link SdkVersionBackend} since mise has no equivalent concept exposed in this app —
     * mise tools are always added implicitly via the registry ("Add SDK") flow instead.
     */
    public CliResult addPlugin(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("add");
        command.addAll(args);
        return cli.run(command, ADD_PLUGIN_TIMEOUT);
    }

    /**
     * Runs {@code vfox upgrade} — updates the vfox binary itself to the latest release,
     * vfox's equivalent of {@code mise self-update}. Not part of {@link SdkVersionBackend}
     * for the same reason as {@link #addPlugin}: mise's self-update goes through
     * {@link com.sampong.tambo.mise.MiseMaintenanceService} instead, which has no vfox
     * implementation.
     */
    public CliResult selfUpdate(Consumer<String> onLine) {
        return cli.runStreaming(List.of("upgrade"), SELF_UPDATE_TIMEOUT, onLine, "vfox-self-update");
    }

    @Override
    public CliResult install(String toolAtVersion, Consumer<String> onLine, String cancelKey) {
        // Registering an already-added plugin is a benign no-op/error; only the install
        // that follows determines success.
        cli.run(List.of("add", toolName(toolAtVersion)), ADD_TIMEOUT);
        return cli.runStreaming(List.of("install", "-y", toolAtVersion), INSTALL_TIMEOUT, onLine, cancelKey);
    }

    @Override
    public CliResult uninstall(String toolAtVersion) {
        return cli.run(List.of("uninstall", toolAtVersion), UNINSTALL_TIMEOUT);
    }

    /**
     * {@code vfox unuse} takes a bare SDK name (no {@code @version}) and requires an explicit
     * scope flag, unlike mise's {@code unuse} which auto-detects where a tool is pinned.
     * Project scope only — global unpinning stays a manual {@code vfox unuse -g} for now.
     */
    @Override
    public CliResult remove(String toolAtVersion) {
        return cli.run(List.of("unuse", "-p", toolName(toolAtVersion)), UNUSE_TIMEOUT);
    }

    /**
     * Unlike mise's {@code use} (which installs {@code tool@version} if needed and pins it in
     * one step), vfox's {@code use} only switches scope — it errors if the version isn't
     * installed yet. So this registers the plugin and installs the version first, same as
     * {@link #install}, before running the actual scope switch.
     */
    @Override
    public CliResult use(String toolAtVersion, boolean global, Consumer<String> onLine, String cancelKey) {
        cli.run(List.of("add", toolName(toolAtVersion)), ADD_TIMEOUT);
        CliResult installResult = cli.runStreaming(List.of("install", "-y", toolAtVersion), INSTALL_TIMEOUT, onLine, cancelKey);
        if (!installResult.ok()) {
            return installResult;
        }
        List<String> args = List.of("use", global ? "-g" : "-p", toolAtVersion);
        return cli.runStreaming(args, USE_TIMEOUT, onLine, cancelKey);
    }

    private static String toolName(String toolAtVersion) {
        return toolAtVersion.contains("@") ? toolAtVersion.substring(0, toolAtVersion.indexOf('@')) : toolAtVersion;
    }

    private static String clean(String rawLine) {
        String noAnsi = ANSI.matcher(rawLine).replaceAll("");
        return TREE_PREFIX.matcher(noAnsi).replaceFirst("").stripTrailing();
    }
}
