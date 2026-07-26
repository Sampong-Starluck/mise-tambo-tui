package com.sampong.tambo.tui.features;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.core.task.AsyncTaskExecutor;

import com.sampong.tambo.mise.CancelRegistry;
import com.sampong.tambo.mise.MiseCli;
import com.sampong.tambo.mise.MiseMaintenanceService;
import com.sampong.tambo.mise.MiseQueryService;
import com.sampong.tambo.mise.MiseToolService;
import com.sampong.tambo.mise.ShellActivationService;
import com.sampong.tambo.mise.model.MiseTask;
import com.sampong.tambo.mise.model.OutdatedTool;
import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo.tui.state.Lazy;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.UiState;

import org.jspecify.annotations.Nullable;

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

    /**
     * Starts the P0 tier before the first frame is composed. Nothing else is
     * fetched here: {@link #ensureDoctor()}, {@link #ensureOutdated()} and
     * {@link #ensureRegistry()} pull in the slower tiers when a panel that
     * actually needs them first renders.
     */
    public void loadInitial() {
        loadEssentials();
    }

    /**
     * Re-reads P0 and marks the derived views stale so whichever panel is on
     * screen reloads them in the background. The registry is deliberately left
     * alone — it lists what mise <em>can</em> install, which no local install,
     * uninstall or trust change affects.
     */
    public void refresh() {
        state.addLog(LogLevel.INFO, "Refreshing…");
        state.toolsLazy().invalidate();
        state.tasksLazy().invalidate();
        state.envLazy().invalidate();
        state.trustLazy().invalidate();
        state.doctorLazy().invalidate();
        state.outdatedLazy().invalidate();
        loadEssentials();
    }

    /**
     * P0 — the four ~10 ms calls behind everything the user reads and acts on
     * straight away. Each publishes into {@link UiState} on its own instead of
     * through a joined snapshot, so no single call can hold up the rest.
     */
    private void loadEssentials() {
        loadLazy(state.toolsLazy(), "tools", query::listTools,
                loaded -> state.addLog(LogLevel.OK, "Loaded " + loaded.size() + " tools"));
        loadLazy(state.tasksLazy(), "tasks", query::listTasks,
                loaded -> state.addLog(LogLevel.OK, "Loaded " + loaded.size() + " tasks"));
        loadLazy(state.envLazy(), "env", query::listEnv, null);
        loadLazy(state.trustLazy(), "trust", query::trustStatus, null);
    }

    // ==================== Lazy tiers ====================

    /**
     * P1 — {@code mise doctor} (~290 ms). Fills the header and Status panel:
     * on screen from the first frame, but nothing the user acts on in that
     * first moment, so it loads on first render rather than delaying the frame.
     */
    public void ensureDoctor() {
        loadLazy(state.doctorLazy(), "doctor", query::doctorSummary, null);
    }

    /**
     * P2 — {@code mise outdated} (~1.2 s cold, network). Only decorates tool
     * rows with "↑ version"; every row renders correctly without it, so it is
     * pulled in behind the already-visible list.
     */
    public void ensureOutdated() {
        loadLazy(state.outdatedLazy(), "outdated", () -> toOutdatedMap(query.listOutdated()), null);
    }

    /**
     * P3 — {@code mise registry} (~200 KB of JSON). Read by nothing but the
     * Add-SDK modal, so it is never fetched unless the user opens it.
     */
    public void ensureRegistry() {
        loadLazy(state.registryLazy(), "registry", query::listRegistry, null);
    }

    /**
     * Runs {@code work} on the executor and publishes it into {@code lazy}, but
     * only if the holder actually wants a load — safe to call from a render
     * method that runs every frame. On failure the claim is released so the
     * next frame retries rather than leaving the panel stuck on its placeholder.
     */
    private <T> void loadLazy(Lazy<T> lazy, String label, Supplier<T> work, @Nullable Consumer<T> onLoaded) {
        int token = lazy.claim();
        if (token == Lazy.NO_LOAD) {
            return;
        }
        executor.execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                uiThread.accept(() -> {
                    lazy.fail(token);
                    state.addLog(LogLevel.ERROR, label + " failed: " + e.getMessage());
                });
                return;
            }
            uiThread.accept(() -> {
                lazy.publish(token, result);
                if (onLoaded != null) {
                    onLoaded.accept(result);
                }
            });
        });
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

    /**
     * Cancels the first of {@code keys} that is running. Failing that, falls back to
     * the single operation that <em>is</em> running, if there is exactly one: 'c' is
     * bound per-panel and cancels whatever the cursor sits on, so moving the
     * selection off a running task — or switching panels to watch its output — used
     * to report "nothing running" while the build carried on in the background.
     * <p>
     * The fallback stays off when several operations are in flight, since there is
     * no way to guess which one was meant; {@link #cancelAll()} handles that case.
     */
    private void cancelFirst(String message, String... keys) {
        for (String key : keys) {
            if (cancelRegistry.cancel(key)) {
                state.addLog(LogLevel.INFO, message);
                return;
            }
        }

        Set<String> live = cancelRegistry.runningKeys();
        if (live.isEmpty()) {
            state.addLog(LogLevel.INFO, "Nothing running to cancel");
            return;
        }
        if (live.size() > 1) {
            state.addLog(LogLevel.INFO, live.size() + " operations running ("
                    + live.stream().map(MiseActions::sourceLabel).sorted().collect(Collectors.joining(", "))
                    + ") — press C to cancel all");
            return;
        }

        String only = live.iterator().next();
        if (cancelRegistry.cancel(only)) {
            state.addLog(LogLevel.INFO, "Cancelled " + sourceLabel(only));
        } else {
            state.addLog(LogLevel.INFO, "Nothing running to cancel");
        }
    }

    /** Kills every in-flight operation, whatever panel or selection is active. */
    public void cancelAll() {
        Set<String> cancelled = cancelRegistry.cancelAll();
        if (cancelled.isEmpty()) {
            state.addLog(LogLevel.INFO, "Nothing running to cancel");
            return;
        }
        state.addLog(LogLevel.INFO, "Cancelled "
                + cancelled.stream().map(MiseActions::sourceLabel).sorted().collect(Collectors.joining(", ")));
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

    /**
     * The substring mise prints when its self-update was compiled out. Matched
     * rather than parsed: mise offers no exit code or flag distinguishing this
     * from an ordinary update failure.
     */
    private static final String SELF_UPDATE_DISABLED_MARKER = "disabled at build time";

    /** Explains why self-update can never work here, in place of mise's raw error. */
    private static final String SELF_UPDATE_DISABLED_MESSAGE =
            "mise's self-update is disabled in this build — it was installed by a package manager, "
                    + "so update it from there";

    /** Runs {@code mise self-update}, streaming progress into the command log. */
    public void selfUpdate() {
        // Once mise has told us the feature is compiled out, that answer holds
        // for the life of this binary: explain instead of spawning a process
        // that is guaranteed to fail.
        if (state.selfUpdateDisabled()) {
            state.addLog(LogLevel.INFO, SELF_UPDATE_DISABLED_MESSAGE);
            return;
        }
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
                    if (!result.ok() && mentionsDisabledSelfUpdate(result)) {
                        state.selfUpdateDisabled(true);
                        state.addLog(LogLevel.INFO, SELF_UPDATE_DISABLED_MESSAGE);
                        return; // nothing changed, so nothing to refresh
                    }
                    logResult(result, "mise is up to date", "Self-update failed");
                    refresh();
                });
    }

    /** True when a failed self-update was refused outright rather than merely erroring. */
    private static boolean mentionsDisabledSelfUpdate(MiseCli.Result result) {
        // self-update streams with stderr merged into stdout, but check both so
        // this keeps working if that ever changes.
        return (result.stdout() + result.stderr()).toLowerCase()
                .contains(SELF_UPDATE_DISABLED_MARKER);
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
