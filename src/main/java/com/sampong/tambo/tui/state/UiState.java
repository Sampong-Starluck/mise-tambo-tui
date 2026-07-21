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

    @NonNull
    private List<ToolVersion> tools = List.of();
    /** Tool short-name → newer version available, from {@code mise outdated}. */
    @NonNull
    private Map<String, String> outdated = Map.of();
    @NonNull
    private List<MiseTask> tasks = List.of();
    @NonNull
    private List<RegistryEntry> registry = List.of();
    @NonNull
    private Map<String, String> env = Map.of();
    @NonNull
    private DoctorInfo doctor = DoctorInfo.unknown();
    @NonNull
    private List<TrustStatus> trust = List.of();
    private boolean loading = true;

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

    /** True when no config directory reported by {@code mise trust --show} is untrusted. */
    public boolean allTrusted() {
        return trust.stream().allMatch(TrustStatus::trusted);
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
