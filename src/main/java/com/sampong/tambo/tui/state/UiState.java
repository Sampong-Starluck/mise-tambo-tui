package com.sampong.tambo.tui.state;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sampong.tambo.mise.model.DoctorInfo;
import com.sampong.tambo.mise.model.MiseTask;
import com.sampong.tambo.mise.model.RegistryEntry;
import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo.mise.model.TrustStatus;

import org.jspecify.annotations.Nullable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Shared UI state: the mise data every panel renders from, the set of in-flight
 * operations, and the command log.
 * <p>
 * Deliberately not thread-safe — it is only ever read or written on the render
 * thread; background work publishes results via {@code runOnRenderThread}.
 */
@Getter
@Setter
@Accessors(fluent = true)
public final class UiState {

    private static final int MAX_LOG = 300;

    // Every dataset is a Lazy: it carries its own load state, so each panel can
    // tell "not fetched yet" from "fetched and genuinely empty" without a shared
    // loading flag that is only ever right for whichever call finished last.
    // See MiseActions for which tier loads eagerly and which loads on first read.
    @Getter(AccessLevel.NONE)
    private final Lazy<List<ToolVersion>> tools = new Lazy<>(List.of());
    /** Tool short-name → newer version available, from {@code mise outdated}. */
    @Getter(AccessLevel.NONE)
    private final Lazy<Map<String, String>> outdated = new Lazy<>(Map.of());
    @Getter(AccessLevel.NONE)
    private final Lazy<List<MiseTask>> tasks = new Lazy<>(List.of());
    @Getter(AccessLevel.NONE)
    private final Lazy<List<RegistryEntry>> registry = new Lazy<>(List.of());
    @Getter(AccessLevel.NONE)
    private final Lazy<Map<String, String>> env = new Lazy<>(Map.of());
    @Getter(AccessLevel.NONE)
    private final Lazy<DoctorInfo> doctor = new Lazy<>(DoctorInfo.unknown());
    @Getter(AccessLevel.NONE)
    private final Lazy<List<TrustStatus>> trust = new Lazy<>(List.of());
    /** Set once at startup from {@code --offline}; gates network-requiring actions. */
    private boolean offline = false;
    /**
     * Set once at startup: true when this project resolved to the vfox backend rather than
     * mise. Panels use this to hide mise-only surfaces (tasks, status, env, trust/doctor/
     * prune/upgrade-all/self-update) that vfox has no equivalent for.
     */
    private boolean vfox = false;
    /**
     * Set when {@code mise self-update} reports that the feature was compiled
     * out — the case for every package-manager build, since the binary is owned
     * by the package database and must not overwrite itself. Learned from the
     * first attempt rather than probed at startup, and from then on the U key
     * explains instead of running and drops out of the footer and help.
     */
    private boolean selfUpdateDisabled = false;
    /**
     * Set at startup from {@code --advanced-features}, and toggleable in-app with {@code V}.
     * Gates the maintenance/config keys (P's --alias/--source syntax, T, E, D, U, X, x, R) and
     * shows the {@link com.sampong.tambo.tui.components.AdvancedPanel} sidebar card that lists
     * them — off by default so a new user's keyboard surface starts small.
     */
    private boolean advancedFeatures = false;

    /** The most recently run task and its args, for the re-run shortcut. */
    @Nullable
    private String lastTaskName;
    @NonNull
    private String lastTaskArgs = "";

    /** In-flight operations, keyed by an operation-specific id (e.g. "node@20" or "task:build"). */
    @Getter(AccessLevel.NONE)
    private final Set<String> busyKeys = new HashSet<>();

    /** The latest streamed output line per in-flight operation, for on-row progress. */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Map<String, String> busyStatus = new java.util.HashMap<>();

    /** Exposed only through {@link #log()} (read-only) and {@link #addLog}, never a raw setter. */
    @Getter(AccessLevel.NONE)
    private final Deque<LogEntry> log = new ArrayDeque<>();

    /** Horizontal pan of the command-log viewport, in columns; 0 = no pan. Custom clamped setter below. */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private int logHScroll;

    // ==================== Data ====================

    // Each dataset is exposed twice: the plain accessor for panels that just want
    // to render the value, and the Lazy holder for those that also need its load
    // state (to show a placeholder) or that trigger the fetch.

    public List<ToolVersion> tools() {
        return tools.value();
    }

    public Lazy<List<ToolVersion>> toolsLazy() {
        return tools;
    }

    public Map<String, String> outdated() {
        return outdated.value();
    }

    public Lazy<Map<String, String>> outdatedLazy() {
        return outdated;
    }

    public List<MiseTask> tasks() {
        return tasks.value();
    }

    public Lazy<List<MiseTask>> tasksLazy() {
        return tasks;
    }

    public List<RegistryEntry> registry() {
        return registry.value();
    }

    public Lazy<List<RegistryEntry>> registryLazy() {
        return registry;
    }

    public Map<String, String> env() {
        return env.value();
    }

    public Lazy<Map<String, String>> envLazy() {
        return env;
    }

    public DoctorInfo doctor() {
        return doctor.value();
    }

    public Lazy<DoctorInfo> doctorLazy() {
        return doctor;
    }

    public List<TrustStatus> trust() {
        return trust.value();
    }

    public Lazy<List<TrustStatus>> trustLazy() {
        return trust;
    }

    /**
     * True when no config directory reported by {@code mise trust --show} is
     * untrusted. Only meaningful once {@link #trustLazy()} has loaded — an empty
     * list vacuously satisfies "all trusted", so callers must check
     * {@link Lazy#everLoaded()} before rendering this as a verdict.
     */
    public boolean allTrusted() {
        return trust.value().stream().allMatch(TrustStatus::trusted);
    }

    // ==================== Busy tracking ====================

    /** Marks an operation as in-flight; returns false when it already is. */
    public boolean markBusy(String key) {
        return !busyKeys.add(key);
    }

    public void clearBusy(String key) {
        busyKeys.remove(key);
        busyStatus.remove(key);
    }

    public boolean isBusy(String key) {
        return busyKeys.contains(key);
    }

    /** Records the newest output line for an in-flight operation. */
    public void busyStatus(String key, String line) {
        busyStatus.put(key, line);
    }

    /** The newest output line for {@code key}, or null when there is none yet. */
    public @Nullable String busyStatusFor(String key) {
        return busyStatus.get(key);
    }

    // ==================== Command log ====================

    public Iterable<LogEntry> log() {
        return log;
    }

    public void addLog(LogLevel level, String text) {
        log.addLast(new LogEntry(level, text));
        while (log.size() > MAX_LOG) {
            log.removeFirst();
        }
    }

    public int logHScroll() {
        return logHScroll;
    }

    /** Pans the log viewport horizontally; never negative, capped by LogPanel per frame. */
    public void logHScroll(int columns) {
        this.logHScroll = Math.max(0, columns);
    }
}
