package com.sampong.tambo.tui.features;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.core.task.AsyncTaskExecutor;

import com.sampong.tambo.mise.CancelRegistry;
import com.sampong.tambo.mise.MiseCli;
import com.sampong.tambo.mise.MiseMaintenanceService;
import com.sampong.tambo.mise.MiseQueryService;
import com.sampong.tambo.mise.MiseToolService;
import com.sampong.tambo.mise.ShellActivationService;
import com.sampong.tambo.mise.model.DoctorInfo;
import com.sampong.tambo.mise.model.MiseTask;
import com.sampong.tambo.mise.model.OutdatedTool;
import com.sampong.tambo.mise.model.RegistryEntry;
import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo.mise.model.TrustStatus;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.UiState;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * All mise operations the UI can trigger. Every call returns immediately: the
 * blocking CLI work runs on the injected executor (virtual threads), and results
 * are published back into {@link UiState} on the render thread. Long-running
 * commands stream their output into the command log live, line by line.
 */
@RequiredArgsConstructor
public final class MiseActions {

    /** Everything the dynamic panels render from, fetched in one background pass. */
    private record Snapshot(List<ToolVersion> tools, List<OutdatedTool> outdated,
                            List<MiseTask> tasks, Map<String, String> env, DoctorInfo doctor,
                            List<TrustStatus> trust) {
    }

    @NonNull
    private final MiseQueryService query;
    @NonNull
    private final MiseToolService tools;
    @NonNull
    private final MiseMaintenanceService maintenance;
    @NonNull
    private final ShellActivationService activation;
    @NonNull
    private final CancelRegistry cancelRegistry;
    @NonNull
    private final AsyncTaskExecutor executor;
    @NonNull
    private final UiState state;
    /** Marshals a runnable onto the TUI render thread. */
    @NonNull
    private final Consumer<Runnable> uiThread;

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

    /** Fetches all independent `mise` calls concurrently on virtual threads. */
    private Snapshot takeSnapshot() {
        CompletableFuture<List<ToolVersion>> tools = supplyAsync(query::listTools);
        CompletableFuture<List<OutdatedTool>> outdated = supplyAsync(query::listOutdated);
        CompletableFuture<List<MiseTask>> tasks = supplyAsync(query::listTasks);
        CompletableFuture<Map<String, String>> env = supplyAsync(query::listEnv);
        CompletableFuture<DoctorInfo> doctor = supplyAsync(query::doctorSummary);
        CompletableFuture<List<TrustStatus>> trust = supplyAsync(query::trustStatus);
        return new Snapshot(tools.join(), outdated.join(), tasks.join(), env.join(),
                doctor.join(), trust.join());
    }

    private <T> CompletableFuture<T> supplyAsync(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, executor);
    }

    private void applySnapshot(Snapshot snapshot) {
        state.tools(snapshot.tools());
        state.outdated(toOutdatedMap(snapshot.outdated()));
        state.tasks(snapshot.tasks());
        state.env(snapshot.env());
        state.doctor(snapshot.doctor());
        state.trust(snapshot.trust());
    }

    /** Flattens the outdated list into a tool → latest-version lookup, ignoring entries without a target. */
    private static Map<String, String> toOutdatedMap(List<OutdatedTool> outdated) {
        Map<String, String> map = new HashMap<>();
        for (OutdatedTool o : outdated) {
            if (o.latest() != null && !o.latest().isBlank()) {
                map.put(o.tool(), o.latest());
            }
        }
        return map;
    }

    // ==================== Tool operations ====================

    public void installTool(@NonNull ToolVersion t) {
        if (blockedOffline("installing")) {
            return;
        }
        String key = t.label();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise install " + key);
        submitBackground("install " + key, key,
                () -> tools.install(key, liveLogLine(key), key),
                result -> {
                    logResult(result, "Installed " + key, "Install failed: " + key);
                    refresh();
                });
    }

    public void uninstallTool(@NonNull ToolVersion t) {
        String key = t.label();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise uninstall " + key);
        submitBackground("uninstall " + key, key,
                () -> tools.uninstall(key),
                result -> {
                    logResult(result, "Uninstalled " + key, "Uninstall failed: " + key);
                    refresh();
                });
    }

    /**
     * Runs {@code mise unuse tool@version} — drops the entry from whichever
     * {@code mise.toml} declares it and prunes the installed version if nothing
     * else still references it.
     */
    public void removeTool(@NonNull ToolVersion t) {
        String key = t.label();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise unuse " + key);
        submitBackground("remove " + key, key,
                () -> tools.remove(key),
                result -> {
                    logResult(result, "Removed " + key + " from mise.toml", "Remove failed: " + key);
                    refresh();
                });
    }

    /** Runs {@code mise use [-g] tool@version} — installs and writes the config entry. */
    public void useTool(@NonNull String toolAtVersion, boolean global) {
        if (blockedOffline("installing")) {
            return;
        }
        String shortName = toolAtVersion.contains("@")
                ? toolAtVersion.substring(0, toolAtVersion.indexOf('@'))
                : toolAtVersion;
        String key = "registry:" + shortName;
        if (state.markBusy(key)) {
            return;
        }
        String args = global ? "mise use -g " + toolAtVersion : "mise use " + toolAtVersion;
        state.addLog(LogLevel.CMD, "$ " + args);
        submitBackground(args, key,
                () -> tools.use(toolAtVersion, global, liveLogLine(key), key),
                result -> {
                    logResult(result,
                            global ? "Set " + toolAtVersion + " as global default"
                                    : "Applied " + toolAtVersion + " to ./mise.toml",
                            "Failed: " + args);
                    refresh();
                });
    }

    /** Runs {@code mise upgrade <tool>} for a single tool, then refreshes. */
    public void upgradeTool(@NonNull ToolVersion t) {
        if (blockedOffline("upgrading")) {
            return;
        }
        String key = "upgrade:" + t.tool();
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise upgrade " + t.tool());
        submitBackground("upgrade " + t.tool(), key,
                () -> tools.upgrade(t.tool(), liveLogLine(key), key),
                result -> {
                    logResult(result, "Upgraded " + t.tool(), "Upgrade failed: " + t.tool());
                    refresh();
                });
    }

    /** Runs {@code mise upgrade} for every outdated tool, then refreshes. */
    public void upgradeAll() {
        if (blockedOffline("upgrading")) {
            return;
        }
        String key = "upgrade:*";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise upgrade");
        submitBackground("upgrade all", key,
                () -> tools.upgrade("", liveLogLine(key), key),
                result -> {
                    logResult(result, "Upgraded all outdated tools", "Upgrade failed");
                    refresh();
                });
    }

    // ==================== Cancellation ====================

    /** Kills any in-flight install / use / upgrade for the given tool. */
    public void cancelTool(@NonNull ToolVersion t) {
        cancelFirst("Cancelled " + t.tool(), t.label(), "registry:" + t.tool(), "upgrade:" + t.tool());
    }

    /** Kills the given task if it is currently running. */
    public void cancelTask(@NonNull String taskName) {
        cancelFirst("Cancelled task " + taskName, "task:" + taskName);
    }

    private void cancelFirst(String message, String... keys) {
        for (String key : keys) {
            if (cancelRegistry.cancel(key)) {
                state.addLog(LogLevel.INFO, message);
                return;
            }
        }
        state.addLog(LogLevel.INFO, "Nothing running to cancel");
    }

    // ==================== Tasks ====================

    /** Runs a task with no extra arguments. */
    public void runTask(@NonNull MiseTask t) {
        runTask(t.name(), "");
    }

    /** Runs {@code mise run <task> [-- args]}, remembering it for {@link #reRunLastTask()}. */
    public void runTask(@NonNull String taskName, @NonNull String args) {
        String key = "task:" + taskName;
        if (state.markBusy(key)) {
            return;
        }
        state.lastTaskName(taskName);
        state.lastTaskArgs(args);
        String display = args.isBlank() ? taskName : taskName + " -- " + args;
        state.addLog(LogLevel.CMD, "$ mise run " + display);
        submitBackground("run " + taskName, key,
                () -> tools.runTask(taskName, args, liveLogLine(key), key),
                result -> logResult(result, "Task \"" + taskName + "\" finished", "Task \"" + taskName + "\" failed"));
    }

    /** Re-runs the last task (with its previous args); logs a hint when nothing has run yet. */
    public void reRunLastTask() {
        String last = state.lastTaskName();
        if (last == null) {
            state.addLog(LogLevel.INFO, "No task has been run yet");
            return;
        }
        runTask(last, state.lastTaskArgs());
    }

    // ==================== Shell activation ====================

    /** Installs the {@code mise activate} line into the user's shell startup file. */
    public void activateMise() {
        String key = "activate";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise activate — writing the activation line to your shell profile");
        submitBackground("activate", key,
                activation::activateInShell,
                outcome -> {
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
        submitBackground("trust", key,
                maintenance::trust,
                result -> {
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
        submitBackground("doctor", key,
                () -> maintenance.doctor(liveLogLine(key)),
                result -> logResult(result, "mise doctor: no problems found", "mise doctor found problems"));
    }

    /** Runs {@code mise prune}, streaming progress, to reclaim disk from old versions. */
    public void prune() {
        String key = "prune";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise prune");
        submitBackground("prune", key,
                () -> maintenance.prune(liveLogLine(key)),
                result -> {
                    logResult(result, "Pruned unused tool versions", "Prune failed");
                    refresh();
                });
    }

    /** Runs {@code mise self-update}, streaming progress into the command log. */
    public void selfUpdate() {
        if (blockedOffline("self-update")) {
            return;
        }
        String key = "self-update";
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "$ mise self-update");
        submitBackground("self-update", key,
                () -> maintenance.selfUpdate(liveLogLine(key)),
                result -> {
                    logResult(result, "mise is up to date", "Self-update failed");
                    refresh();
                });
    }

    // ==================== Config files ====================

    /** Writes edited config content to disk, then refreshes so mise picks it up. */
    public void saveConfig(@NonNull Path file, @NonNull String content) {
        String key = "save:" + file;
        if (state.markBusy(key)) {
            return;
        }
        state.addLog(LogLevel.CMD, "Saving " + file + "…");
        submitBackground("save " + file, key,
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
                    if (error.isEmpty()) {
                        state.addLog(LogLevel.OK, "Saved " + file);
                        refresh();
                        validateConfigAsync();
                    } else {
                        state.addLog(LogLevel.ERROR, "Could not save " + file + ": " + error);
                    }
                });
    }

    /**
     * After a config save, parses the active config in the background and logs a
     * warning if mise reports a TOML parse error — catching a broken edit early.
     */
    private void validateConfigAsync() {
        submitBackground("validate config",
                maintenance::validateConfig,
                result -> {
                    String stderr = result.stderr();
                    if (stderr.toLowerCase().contains("parse error")) {
                        state.addLog(LogLevel.ERROR, "Config parse error — " + firstLine(stderr));
                    }
                });
    }

    private static String firstLine(String text) {
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    // ==================== Registry ====================

    /** Fetches installable versions of a tool; {@code onDone} runs on the render thread. */
    public void fetchRemoteVersions(@NonNull String tool, @NonNull Consumer<List<String>> onDone) {
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
     * onto the render thread and appends it to the command log as it arrives,
     * prefixed with a short source tag so concurrently running operations stay
     * distinguishable in the shared log. Also records the (untagged) line as the
     * live status of the operation under {@code busyKey}, so its panel row can
     * show progress.
     */
    private Consumer<String> liveLogLine(String busyKey) {
        String tag = "[" + sourceLabel(busyKey) + "] ";
        return line -> {
            if (!line.isBlank()) {
                uiThread.accept(() -> {
                    state.addLog(LogLevel.INFO, tag + line);
                    state.busyStatus(busyKey, line);
                });
            }
        };
    }

    /** Derives a short, human-readable source tag from an operation's busy key. */
    private static String sourceLabel(String busyKey) {
        if (busyKey.startsWith("task:")) {
            return busyKey.substring("task:".length());
        }
        if (busyKey.startsWith("registry:")) {
            return busyKey.substring("registry:".length());
        }
        if (busyKey.startsWith("upgrade:")) {
            String tool = busyKey.substring("upgrade:".length());
            return tool.equals("*") ? "upgrade" : "upgrade " + tool;
        }
        if (busyKey.startsWith("save:")) {
            return "save";
        }
        return busyKey;
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

    /**
     * Like {@link #submitBackground(String, Supplier, Consumer)} but also clears
     * {@code busyKey} before invoking the callback — including on the exception
     * path, which the plain overload leaves marked busy forever since nothing
     * else would clear it. Every action that calls {@link UiState#markBusy} up
     * front must release it through this overload.
     */
    private <T> void submitBackground(String label, String busyKey, Supplier<T> work, Consumer<T> onDone) {
        executor.execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                uiThread.accept(() -> {
                    state.clearBusy(busyKey);
                    state.addLog(LogLevel.ERROR, label + " failed: " + e.getMessage());
                });
                return;
            }
            uiThread.accept(() -> {
                state.clearBusy(busyKey);
                onDone.accept(result);
            });
        });
    }

    /**
     * Logs a message and returns {@code true} when the app was launched with
     * {@code --offline} and {@code what} needs the network — the caller should
     * bail out without marking anything busy or touching the CLI.
     */
    private boolean blockedOffline(String what) {
        if (query.offline()) {
            state.addLog(LogLevel.INFO, "Offline mode — " + what + " needs network access");
            return true;
        }
        return false;
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
