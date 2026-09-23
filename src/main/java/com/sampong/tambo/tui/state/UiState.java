package com.sampong.tambo.tui.state;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.ProjectTask;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.model.TrustState;
import com.sampong.tambo._common.service.SdkVersionBackend;

import org.jspecify.annotations.Nullable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Shared UI state: the backend-neutral data every panel renders from, the set of in-flight
 * operations, and the command log.
 * <p>
 * Deliberately not thread-safe — it is only ever read or written on the render thread;
 * background work publishes results via {@code runOnRenderThread}.
 */
@Getter
@Setter
@Accessors(fluent = true)
public final class UiState {

    private static final int MAX_LOG = 300;

    // Every dataset is a Lazy: it carries its own load state, so each panel can
    // tell "not fetched yet" from "fetched and genuinely empty" without a shared
    // loading flag that is only ever right for whichever call finished last.
    // See BackendActions for which tier loads eagerly and which loads on first read.
    @Getter(AccessLevel.NONE)
    private final Lazy<List<SdkVersion>> sdks = new Lazy<>(List.of());
    /** SDK short-name → newer version available; only populated by backends that report it. */
    @Getter(AccessLevel.NONE)
    private final Lazy<Map<String, String>> outdated = new Lazy<>(Map.of());
    @Getter(AccessLevel.NONE)
    private final Lazy<List<ProjectTask>> tasks = new Lazy<>(List.of());
    @Getter(AccessLevel.NONE)
    private final Lazy<List<CatalogEntry>> catalog = new Lazy<>(List.of());
    @Getter(AccessLevel.NONE)
    private final Lazy<Map<String, String>> env = new Lazy<>(Map.of());
    /**
     * The active backend's version and, where it has one, its health report. Replaces the
     * separate mise-doctor and vfox-version holders this used to keep: both backends answer
     * {@code info()} now, so there is one thing to load and one thing for the header to read.
     */
    @Getter(AccessLevel.NONE)
    private final Lazy<BackendInfo> backendInfo = new Lazy<>(BackendInfo.unknown("mise"));
    @Getter(AccessLevel.NONE)
    private final Lazy<List<TrustState>> trust = new Lazy<>(List.of());
    /** Set once at startup from {@code --offline}; gates network-requiring actions. */
    private boolean offline = false;
    /**
     * Set when {@code self-update} reports that the feature was compiled out — the case for
     * every package-manager build, since the binary is owned by the package database and must
     * not overwrite itself. Learned from the first attempt rather than probed at startup, and
     * from then on the U key explains instead of running and drops out of the footer and help.
     */
    private boolean selfUpdateDisabled = false;
    /**
     * Set at startup from {@code --advanced-features}, and toggleable in-app with {@code V}.
     * Gates the maintenance/config keys and shows the
     * {@link com.sampong.tambo.tui.components.AdvancedPanel} that lists them — off by default
     * so a new user's keyboard surface starts small.
     */
    private boolean advancedFeatures = false;

    /**
     * The version manager this session is driving, resolved by {@code AppLifecycle} before the
     * first render. Everything the UI used to decide by asking "is this vfox?" now asks this
     * what it supports instead, so a backend gaining a feature needs no change in any panel.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private @Nullable SdkVersionBackend backend;

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

    // ==================== Backend ====================

    /**
     * The active backend. Throws before {@code AppLifecycle} has resolved one, which would be a
     * render before the session was set up — a bug worth failing loudly on rather than papering
     * over with a mise default that would silently run the wrong CLI.
     */
    public SdkVersionBackend backend() {
        return Objects.requireNonNull(backend, "backend not resolved yet");
    }

    public void backend(SdkVersionBackend backend) {
        this.backend = backend;
    }

    // ==================== Data ====================

    // Each dataset is exposed twice: the plain accessor for panels that just want
    // to render the value, and the Lazy holder for those that also need its load
    // state (to show a placeholder) or that trigger the fetch.

    public List<SdkVersion> sdks() {
        return sdks.value();
    }

    public Lazy<List<SdkVersion>> sdksLazy() {
        return sdks;
    }

    public Map<String, String> outdated() {
        return outdated.value();
    }

    public Lazy<Map<String, String>> outdatedLazy() {
        return outdated;
    }

    public List<ProjectTask> tasks() {
        return tasks.value();
    }

    public Lazy<List<ProjectTask>> tasksLazy() {
        return tasks;
    }

    public List<CatalogEntry> catalog() {
        return catalog.value();
    }

    public Lazy<List<CatalogEntry>> catalogLazy() {
        return catalog;
    }

    public Map<String, String> env() {
        return env.value();
    }

    public Lazy<Map<String, String>> envLazy() {
        return env;
    }

    public BackendInfo backendInfo() {
        return backendInfo.value();
    }

    public Lazy<BackendInfo> backendInfoLazy() {
        return backendInfo;
    }

    public List<TrustState> trust() {
        return trust.value();
    }

    public Lazy<List<TrustState>> trustLazy() {
        return trust;
    }

    /**
     * True when no config directory the backend reported is untrusted. Only meaningful once
     * {@link #trustLazy()} has loaded — an empty list vacuously satisfies "all trusted", so
     * callers must check {@link Lazy#everLoaded()} before rendering this as a verdict.
     */
    public boolean allTrusted() {
        return trust.value().stream().allMatch(TrustState::trusted);
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

    /** True while any operation at all is running, for the header's activity indicator. */
    public boolean anyBusy() {
        return !busyKeys.isEmpty();
    }

    /** How many operations are in flight, for the header's activity indicator. */
    public int busyCount() {
        return busyKeys.size();
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
}
