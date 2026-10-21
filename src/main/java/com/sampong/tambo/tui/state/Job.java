package com.sampong.tambo.tui.state;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.jspecify.annotations.Nullable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * One background operation — an install, an upgrade, a removal — as the jobs view shows it.
 * <p>
 * A job exists from the moment it is queued, so the user can see work that is waiting for a free
 * slot rather than having it silently absent. Only {@link JobTracker} changes a job; the fields
 * are package-private setters so the tracker can keep the timestamps and state in step.
 * <p>
 * The output is bounded: a long install can stream thousands of lines, and the view only ever
 * needs the tail to diagnose a failure. The full stream still reaches the command log.
 */
@Getter
@Accessors(fluent = true)
public final class Job {

    /** Lines of output kept per job; older lines fall off the top. */
    static final int MAX_OUTPUT = 500;

    public enum State {
        QUEUED, RUNNING, DONE, FAILED, CANCELLED
    }

    private final long id;
    /** What the job is for, e.g. {@code install node@23.1.0}. */
    private final String label;
    /** The command as echoed to the log, e.g. {@code $ mise install node@23.1.0}. */
    private final String command;
    /** The UiState busy key this job holds while it runs, e.g. {@code node@23.1.0}. */
    private final String busyKey;
    /** Re-runs the same operation; null when the job cannot be retried from the view. */
    private final @Nullable Runnable retry;
    private final long queuedAtNanos;

    @Setter(AccessLevel.PACKAGE)
    private State state = State.QUEUED;
    /** {@link System#nanoTime()} when the job started; 0 until it does. */
    @Setter(AccessLevel.PACKAGE)
    private long startedAtNanos;
    /** {@link System#nanoTime()} when the job finished, or was cancelled; 0 until then. */
    @Setter(AccessLevel.PACKAGE)
    private long finishedAtNanos;
    /** One-line failure summary; shown in place of the progress bar and copied by {@code y}. */
    @Setter(AccessLevel.PACKAGE)
    private @Nullable String error;
    /** One-line success note, e.g. {@code mise.toml updated}. */
    @Setter(AccessLevel.PACKAGE)
    private @Nullable String note;

    @Getter(AccessLevel.NONE)
    private final Deque<String> output = new ArrayDeque<>();

    Job(long id, String label, String command, String busyKey, @Nullable Runnable retry, long queuedAtNanos) {
        this.id = id;
        this.label = label;
        this.command = command;
        this.busyKey = busyKey;
        this.retry = retry;
        this.queuedAtNanos = queuedAtNanos;
    }

    /** Queued or running — the states in which the job still holds its busy key. */
    public boolean isActive() {
        return state == State.QUEUED || state == State.RUNNING;
    }

    /** Done, failed or cancelled: the job no longer holds anything and can be dismissed. */
    public boolean isFinished() {
        return !isActive();
    }

    /** Appends a line, dropping the oldest once {@link #MAX_OUTPUT} is reached. */
    void appendOutput(String line) {
        output.addLast(line);
        while (output.size() > MAX_OUTPUT) {
            output.removeFirst();
        }
    }

    /** The retained output, oldest first. A copy, so a caller may hold it across frames. */
    public List<String> output() {
        return List.copyOf(output);
    }

    /** The newest output line, or null when the job has printed nothing yet. */
    public @Nullable String lastOutput() {
        return output.peekLast();
    }
}
