package com.sampong.tambo.tui;

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

/**
 * Shared UI state: the mise data every panel renders from, the set of in-flight
 * operations, and the command log.
 * <p>
 * Deliberately not thread-safe — it is only ever read or written on the render
 * thread; background work publishes results via {@code runOnRenderThread}.
 */
public final class UiState {

    private static final int MAX_LOG = 300;

    private List<ToolVersion> tools = List.of();
    private List<MiseTask> tasks = List.of();
    private List<RegistryEntry> registry = List.of();
    private Map<String, String> env = Map.of();
    private DoctorInfo doctor = DoctorInfo.unknown();
    private boolean loading = true;

    /** In-flight operations, keyed by an operation-specific id (e.g. "node@20" or "task:build"). */
    private final Set<String> busyKeys = new HashSet<>();

    private final Deque<LogEntry> log = new ArrayDeque<>();

    // ==================== Data ====================

    public List<ToolVersion> tools() {
        return tools;
    }

    public void tools(List<ToolVersion> tools) {
        this.tools = tools;
    }

    public List<MiseTask> tasks() {
        return tasks;
    }

    public void tasks(List<MiseTask> tasks) {
        this.tasks = tasks;
    }

    public List<RegistryEntry> registry() {
        return registry;
    }

    public void registry(List<RegistryEntry> registry) {
        this.registry = registry;
    }

    public Map<String, String> env() {
        return env;
    }

    public void env(Map<String, String> env) {
        this.env = env;
    }

    public DoctorInfo doctor() {
        return doctor;
    }

    public void doctor(DoctorInfo doctor) {
        this.doctor = doctor;
    }

    public boolean loading() {
        return loading;
    }

    public void loading(boolean loading) {
        this.loading = loading;
    }

    // ==================== Busy tracking ====================

    /** Marks an operation as in-flight; returns false when it already is. */
    public boolean markBusy(String key) {
        return !busyKeys.add(key);
    }

    public void clearBusy(String key) {
        busyKeys.remove(key);
    }

    public boolean isBusy(String key) {
        return busyKeys.contains(key);
    }

    // ==================== Command log ====================

    public Iterable<LogEntry> log() {
        return log;
    }

    public boolean logEmpty() {
        return log.isEmpty();
    }

    public void addLog(LogLevel level, String text) {
        log.addLast(new LogEntry(level, text));
        while (log.size() > MAX_LOG) {
            log.removeFirst();
        }
    }
}
