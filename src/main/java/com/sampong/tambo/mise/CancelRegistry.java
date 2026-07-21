package com.sampong.tambo.mise;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.NonNull;

/**
 * Tracks the live {@link Process} of each streaming {@code mise} command, keyed
 * by the caller's operation key (the same key {@code UiState} uses to mark the
 * operation busy). Lets the UI kill a long-running install/task on demand.
 * <p>
 * Thread-safe: processes are registered on the reader/worker threads and
 * cancelled from the render thread.
 */
@Component
public class CancelRegistry {

    private final Map<String, Process> running = new ConcurrentHashMap<>();

    public void register(@NonNull String key, @NonNull Process process) {
        running.put(key, process);
    }

    public void deregister(@NonNull String key) {
        running.remove(key);
    }

    /** True when an operation under {@code key} is currently cancellable. */
    public boolean isRunning(@NonNull String key) {
        return running.containsKey(key);
    }

    /**
     * Forcibly terminates the process registered under {@code key}, if any.
     *
     * @return true when a process was found and asked to stop
     */
    public boolean cancel(@NonNull String key) {
        Process process = running.remove(key);
        if (process == null) {
            return false;
        }
        process.destroy();
        process.destroyForcibly();
        return true;
    }
}
