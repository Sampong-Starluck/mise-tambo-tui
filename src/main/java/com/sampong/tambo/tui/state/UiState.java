package com.sampong.tambo.tui.state;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.InstallProgress;
import com.sampong.tambo._common.model.ProjectTask;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.model.TrustState;
import com.sampong.tambo._common.service.SdkVersionBackend;
import com.sampong.tambo.tui.features.ConfigHistory;

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

    /**
     * The latest parsed download progress per in-flight operation, and within it per tool —
     * one operation can install several ({@code mise upgrade} with no argument), each with its
     * own bar. Insertion-ordered both ways, so "the first" is the oldest.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Map<String, Map<String, ProgressSnapshot>> progress = new java.util.LinkedHashMap<>();

    /**
     * Tool names marked with {@code m} for a batch action — yazi's selection. Names rather than
     * row indexes, so a mark survives the list being filtered, re-sorted or refreshed.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Set<String> marked = new LinkedHashSet<>();

    /** Midnight Commander mode: a menu bar on top and an F-key bar at the bottom. {@code Space m}. */
    private boolean beginnerMode = false;
    /** Ctrl+O: panel titles show jump labels and the next key picks one. */
    private boolean jumpMode = false;
    /** {@code +} in Tools: the table takes the whole body, with latest/where columns. */
    private boolean toolsFullScreen = false;
    /** {@code o} in Tools: only rows with a newer version available. */
    private boolean outdatedOnly = false;
    /**
     * On a terminal too narrow for columns side by side, the detail pane replaces the Versions
     * column after Enter, k9s-style, until Esc goes back.
     */
    private boolean narrowDetail = false;
    /**
     * When the tool list last loaded, for the offline banner's "showing data from 12:01" —
     * stale data is still worth showing, as long as it says how stale.
     */
    private @Nullable LocalTime loadedAt;

    /**
     * Installs, upgrades and the like, for the {@code w} jobs view. Here rather than in the
     * actions layer because that is rebuilt when the backend is picked, and the jobs view holds
     * on to this from the first frame.
     */
    private final JobTracker jobs = new JobTracker();
    /** Every config change this session made, for {@code z}/{@code Z}. */
    private final ConfigHistory history = new ConfigHistory();

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

    /**
     * Marks an operation as in-flight.
     *
     * @return true when it was idle and is now marked; false when it was already running, in
     *         which case the caller must not start it a second time
     */
    public boolean tryMarkBusy(String key) {
        return busyKeys.add(key);
    }

    public void clearBusy(String key) {
        busyKeys.remove(key);
        busyStatus.remove(key);
        progress.remove(key);
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

    /**
     * Records a progress reading about {@code subject} (a {@code tool@version}) within the
     * in-flight operation {@code key}.
     *
     * @return true when it starts a new phase for that subject (or is its first reading), the
     *         moment worth a line in the command log; the frames within a phase are not
     */
    public boolean progress(String key, String subject, InstallProgress reading) {
        long now = System.nanoTime();
        Map<String, ProgressSnapshot> subjects = progress.computeIfAbsent(key, k -> new java.util.LinkedHashMap<>());
        ProgressSnapshot previous = subjects.get(subject);
        subjects.put(subject, previous == null
                ? ProgressSnapshot.first(subject, reading, now)
                : previous.advance(reading, now));
        return previous == null || previous.progress().phase() != reading.phase();
    }

    /**
     * The most recent progress reading of operation {@code key}, or null when its output has
     * reported none. For a single-SDK operation that is simply its progress.
     */
    public @Nullable ProgressSnapshot progressFor(String key) {
        Map<String, ProgressSnapshot> subjects = progress.get(key);
        if (subjects == null) {
            return null;
        }
        return subjects.values().stream()
                .max(java.util.Comparator.comparingLong(ProgressSnapshot::receivedAtNanos))
                .orElse(null);
    }

    /**
     * Within operation {@code key}, the progress of whichever subject is a version of
     * {@code toolName} — for finding one tool's bar inside a multi-tool upgrade.
     */
    public @Nullable ProgressSnapshot progressFor(String key, String toolName) {
        Map<String, ProgressSnapshot> subjects = progress.get(key);
        if (subjects == null) {
            return null;
        }
        for (ProgressSnapshot snapshot : subjects.values()) {
            if (snapshot.toolName().equals(toolName)) {
                return snapshot;
            }
        }
        return null;
    }

    /** Every subject of every operation currently reporting progress, oldest first. */
    public List<ProgressSnapshot> allProgress() {
        return progress.values().stream().flatMap(subjects -> subjects.values().stream()).toList();
    }

    // ==================== Toast ====================

    /** How long a toast stays above the footer. */
    private static final long TOAST_NANOS = 8_000_000_000L;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private @Nullable String toast;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean toastUndoable;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private long toastAtNanos;

    /**
     * Shows {@code message} on the line above the footer for a few seconds — the one-line
     * receipt a config change leaves ("node 22.9.0 → 22.11.0 in mise.toml · z undo"), so the
     * undo key is offered at the moment it is most likely to be wanted.
     */
    public void toast(String message, boolean undoable) {
        toast = message;
        toastUndoable = undoable;
        toastAtNanos = System.nanoTime();
    }

    /** The current toast, or null once it has expired. */
    public @Nullable String toast() {
        return toast != null && System.nanoTime() - toastAtNanos < TOAST_NANOS ? toast : null;
    }

    /** Whether the current toast is about a change {@code z} can take back. */
    public boolean toastUndoable() {
        return toastUndoable;
    }

    // ==================== Batch marks ====================

    /** Marks {@code tool} if it was not, unmarks it if it was; returns whether it is now marked. */
    public boolean toggleMark(String tool) {
        if (marked.remove(tool)) {
            return false;
        }
        marked.add(tool);
        return true;
    }

    public void mark(String tool) {
        marked.add(tool);
    }

    public boolean isMarked(String tool) {
        return marked.contains(tool);
    }

    public void clearMarks() {
        marked.clear();
    }

    /** The marked tool names in the order they were marked; a copy, safe to iterate while acting. */
    public List<String> marked() {
        return List.copyOf(marked);
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
