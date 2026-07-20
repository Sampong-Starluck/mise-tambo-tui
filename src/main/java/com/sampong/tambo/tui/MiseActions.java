package com.sampong.tambo.tui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.core.task.AsyncTaskExecutor;

import com.sampong.tambo.mise.MiseCli;
import com.sampong.tambo.mise.MiseMaintenanceService;
import com.sampong.tambo.mise.MiseQueryService;
import com.sampong.tambo.mise.MiseToolService;
import com.sampong.tambo.mise.ShellActivationService;
import com.sampong.tambo.mise.model.DoctorInfo;
import com.sampong.tambo.mise.model.MiseTask;
import com.sampong.tambo.mise.model.RegistryEntry;
import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo.mise.model.TrustStatus;

/**
 * All mise operations the UI can trigger. Every call returns immediately: the
 * blocking CLI work runs on the injected executor (virtual threads), and results
 * are published back into {@link UiState} on the render thread. Long-running
 * commands stream their output into the command log live, line by line.
 */
public final class MiseActions {

    /** Everything the dynamic panels render from, fetched in one background pass. */
    private record Snapshot(List<ToolVersion> tools, List<MiseTask> tasks,
                            Map<String, String> env, DoctorInfo doctor,
                            List<TrustStatus> trust) {
    }

    private final MiseQueryService query;
    private final MiseToolService tools;
    private final MiseMaintenanceService maintenance;
    private final ShellActivationService activation;
    private final AsyncTaskExecutor executor;
    private final UiState state;
    /** Marshals a runnable onto the TUI render thread. */
    private final Consumer<Runnable> uiThread;

    public MiseActions(MiseQueryService query, MiseToolService tools,
                       MiseMaintenanceService maintenance, ShellActivationService activation,
                       AsyncTaskExecutor executor, UiState state, Consumer<Runnable> uiThread) {
        this.query = query;
        this.tools = tools;
        this.maintenance = maintenance;
        this.activation = activation;
        this.executor = executor;
        this.state = state;
        this.uiThread = uiThread;
    }

    // ==================== Loading / refresh ====================

    public void loadInitial() {
        submitBackground("initial load",
                () -> {
                    // Forked before takeSnapshot() blocks on its own futures, so all six
                    // independent `mise` invocations run concurrently on virtual threads.
                    CompletableFuture<List<RegistryEntry>> registryFuture = supplyAsync(query::listRegistry);
                    Snapshot snapshot = takeSnapshot();
                    return Map.entry(registryFuture.join(), snapshot);
                },
                loaded -> {
                    state.registry(loaded.getKey());
                    applySnapshot(loaded.getValue());
                    state.loading(false);
                    state.addLog(LogLevel.OK, "Loaded " + state.tools().size() + " tools, "
                            + state.tasks().size() + " tasks, " + state.registry().size() + " registry entries");
                });
    }

    public void refresh() {
        state.addLog(LogLevel.INFO, "Refreshing…");
        submitBackground("refresh",
                this::takeSnapshot,
                snapshot -> {
                    applySnapshot(snapshot);
                    state.addLog(LogLevel.OK, "Refreshed " + state.tools().size() + " tools, "
                            + state.tasks().size() + " tasks");
                });
    }

    /** Fetches all five independent `mise` calls concurrently on virtual threads. */
    private Snapshot takeSnapshot() {
        CompletableFuture<List<ToolVersion>> tools = supplyAsync(query::listTools);
        CompletableFuture<List<MiseTask>> tasks = supplyAsync(query::listTasks);
        CompletableFuture<Map<String, String>> env = supplyAsync(query::listEnv);
        CompletableFuture<DoctorInfo> doctor = supplyAsync(query::doctorSummary);
        CompletableFuture<List<TrustStatus>> trust = supplyAsync(query::trustStatus);
        return new Snapshot(tools.join(), tasks.join(), env.join(), doctor.join(), trust.join());
    }

    private <T> CompletableFuture<T> supplyAsync(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, executor);
    }

    private void applySnapshot(Snapshot snapshot) {
        state.tools(snapshot.tools());
        state.tasks(snapshot.tasks());
        state.env(snapshot.env());
        state.doctor(snapshot.doctor());
        state.trust(snapshot.trust());
    }

    // ==================== Tool operations ====================

    public void installTool(ToolVersion t) {
        String key = t.label();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise install " + key);
        submitBackground("install " + key,
                () -> tools.install(key, liveLogLine()),
                result -> {
                    state.clearBusy(key);
                    logResult(result, "Installed " + key, "Install failed: " + key);
                    refresh();
                });
    }

    public void uninstallTool(ToolVersion t) {
        String key = t.label();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise uninstall " + key);
        submitBackground("uninstall " + key,
                () -> tools.uninstall(key),
                result -> {
                    state.clearBusy(key);
                    logResult(result, "Uninstalled " + key, "Uninstall failed: " + key);
                    refresh();
                });
    }

    /** Runs {@code mise use [-g] tool@version} — installs and writes the config entry. */
    public void useTool(String toolAtVersion, boolean global) {
        String shortName = toolAtVersion.contains("@")
                ? toolAtVersion.substring(0, toolAtVersion.indexOf('@'))
                : toolAtVersion;
        String key = "registry:" + shortName;
        if (state.markBusy(key)) {
            return;
        }
        String args = global ? "mise use -g " + toolAtVersion : "mise use " + toolAtVersion;
        state.addLog(LogLevel.CMD, "$ " + args);
        submitBackground(args,
                () -> tools.use(toolAtVersion, global, liveLogLine()),
                result -> {
                    state.clearBusy(key);
                    logResult(result,
                            global ? "Set " + toolAtVersion + " as global default"
                                    : "Applied " + toolAtVersion + " to ./mise.toml",
                            "Failed: " + args);
                    refresh();
                });
    }

    // ==================== Tasks ====================

    public void runTask(MiseTask t) {
        String key = "task:" + t.name();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise run " + t.name());
        submitBackground("run " + t.name(),
                () -> tools.runTask(t.name(), liveLogLine()),
                result -> {
                    state.clearBusy(key);
                    logResult(result, "Task \"" + t.name() + "\" finished", "Task \"" + t.name() + "\" failed");
                });
    }

    // ==================== Shell activation ====================

    /** Installs the {@code mise activate} line into the user's shell startup file. */
    public void activateMise() {
        String key = "activate";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise activate — writing the activation line to your shell profile");
        submitBackground("activate",
                activation::activateInShell,
                outcome -> {
                    state.clearBusy(key);
                    LogLevel level = !outcome.ok() ? LogLevel.ERROR : outcome.changed() ? LogLevel.OK : LogLevel.INFO;
                    state.addLog(level, outcome.message());
                    refresh();
                });
    }

    /** Runs {@code mise trust} so mise is allowed to parse this project's config. */
    public void trustProject() {
        String key = "trust";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise trust");
        submitBackground("trust",
                maintenance::trust,
                result -> {
                    state.clearBusy(key);
                    logResult(result, "Config trusted — mise will now load this project's mise.toml",
                            "mise trust failed");
                    refresh();
                });
    }

    // ==================== Maintenance ====================

    /** Streams the full {@code mise doctor} report into the command log. */
    public void runDoctor() {
        String key = "doctor";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise doctor");
        submitBackground("doctor",
                () -> maintenance.doctor(liveLogLine()),
                result -> {
                    state.clearBusy(key);
                    logResult(result, "mise doctor: no problems found", "mise doctor found problems");
                });
    }

    /** Runs {@code mise self-update}, streaming progress into the command log. */
    public void selfUpdate() {
        String key = "self-update";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise self-update");
        submitBackground("self-update",
                () -> maintenance.selfUpdate(liveLogLine()),
                result -> {
                    state.clearBusy(key);
                    logResult(result, "mise is up to date", "Self-update failed");
                    refresh();
                });
    }

    // ==================== Config files ====================

    /** Writes edited config content to disk, then refreshes so mise picks it up. */
    public void saveConfig(Path file, String content) {
        String key = "save:" + file;
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "Saving " + file + "…");
        submitBackground("save " + file,
                () -> {
                    try {
                        if (file.getParent() != null) {
                            Files.createDirectories(file.getParent());
                        }
                        Files.writeString(file, content);
                        return "";
                    } catch (IOException e) {
                        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    }
                },
                error -> {
                    state.clearBusy(key);
                    if (error.isEmpty()) {
                        state.addLog(LogLevel.OK, "Saved " + file);
                        refresh();
                    } else {
                        state.addLog(LogLevel.ERROR, "Could not save " + file + ": " + error);
                    }
                });
    }

    // ==================== Registry ====================

    /** Fetches installable versions of a tool; {@code onDone} runs on the render thread. */
    public void fetchRemoteVersions(String tool, Consumer<List<String>> onDone) {
        state.addLog(LogLevel.CMD, "$ mise ls-remote " + tool);
        submitBackground("ls-remote " + tool,
                () -> query.listRemoteVersions(tool),
                versions -> {
                    state.addLog(LogLevel.OK, (versions.size() - 1) + " versions of " + tool + " available");
                    onDone.accept(versions);
                });
    }

    // ==================== Plumbing ====================

    /**
     * A line consumer for streaming commands: marshals every non-blank output line
     * onto the render thread and appends it to the command log as it arrives.
     */
    private Consumer<String> liveLogLine() {
        return line -> {
            if (!line.isBlank()) {
                uiThread.accept(() -> state.addLog(LogLevel.INFO, line));
            }
        };
    }

    private <T> void submitBackground(String label, Supplier<T> work, Consumer<T> onDone) {
        executor.execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                uiThread.accept(() -> state.addLog(LogLevel.ERROR, label + " failed: " + e.getMessage()));
                return;
            }
            uiThread.accept(() -> onDone.accept(result));
        });
    }

    private void logResult(MiseCli.Result result, String okMessage, String failMessagePrefix) {
        if (result.ok()) {
            state.addLog(LogLevel.OK, okMessage);
        } else {
            String detail = result.summaryLine();
            state.addLog(LogLevel.ERROR, failMessagePrefix + (detail.isBlank() ? "" : ": " + detail));
        }
    }
}
