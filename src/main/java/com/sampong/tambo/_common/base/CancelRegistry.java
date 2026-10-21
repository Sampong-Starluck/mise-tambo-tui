package com.sampong.tambo._common.base;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import lombok.NonNull;

/**
 * Tracks the live {@link Process} of each streaming {@code mise} command, keyed
 * by the caller's operation key (the same key {@code UiState} uses to mark the
 * operation busy). Lets the UI kill a long-running install/task on demand.
 * <p>
 * Also tracks, unkeyed, every other subprocess in flight — the short blocking calls
 * ({@code ls}, {@code uninstall}, {@code ls-remote}, …) that the user has no key to cancel —
 * so that {@link #destroy()} can take both kinds down when the app exits. Without that, quitting
 * mid-task left {@code mise run dev} or an install running orphaned after the TUI was gone.
 * <p>
 * Thread-safe: processes are registered on the reader/worker threads and
 * cancelled from the render thread.
 */
@Component
public class CancelRegistry implements DisposableBean {

    private final Map<String, Process> running = new ConcurrentHashMap<>();
    private final Set<Process> unkeyed = ConcurrentHashMap.newKeySet();

    public void register(@NonNull String key, @NonNull Process process) {
        running.put(key, process);
    }

    public void deregister(@NonNull String key) {
        running.remove(key);
    }

    /** Records a process with no cancel key, so it is still killed on exit; see {@link #destroy()}. */
    public void track(@NonNull Process process) {
        unkeyed.add(process);
    }

    public void untrack(@NonNull Process process) {
        unkeyed.remove(process);
    }

    /** True when an operation under {@code key} is currently cancellable. */
    public boolean isRunning(@NonNull String key) {
        return running.containsKey(key);
    }

    /**
     * Keys of every operation currently cancellable, in no particular order.
     * Lets the UI cancel what is actually running rather than only what the
     * cursor happens to be sitting on.
     */
    public Set<String> runningKeys() {
        return Set.copyOf(running.keySet());
    }

    /**
     * Cancels every registered operation.
     *
     * @return the keys that were cancelled
     */
    public Set<String> cancelAll() {
        Set<String> cancelled = new HashSet<>();
        for (String key : running.keySet()) {
            if (cancel(key)) {
                cancelled.add(key);
            }
        }
        return cancelled;
    }

    /**
     * Forcibly terminates the process registered under {@code key}, if any —
     * including its descendants. {@code mise run}/{@code install} typically forks a
     * shell or build tool to do the real work; killing only the direct {@code mise}
     * child leaves that descendant running and holding the output pipe open, which
     * hangs the reader thread waiting for an EOF that never comes.
     *
     * @return true when a process was found and asked to stop
     */
    public boolean cancel(@NonNull String key) {
        Process process = running.remove(key);
        if (process == null) {
            return false;
        }
        kill(process);
        return true;
    }

    /**
     * Kills every subprocess still running, keyed or not, when the Spring context closes —
     * which happens right after the TUI quits ({@code SpringApplication.exit}) and on
     * SIGTERM / Ctrl+C via Spring's shutdown hook.
     */
    @Override
    public void destroy() {
        cancelAll();
        for (Process process : unkeyed) {
            kill(process);
        }
        unkeyed.clear();
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroy();
        process.destroyForcibly();
    }
}
