package com.sampong.tambo.tui.state;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * The register of background operations behind the jobs view and the header's activity line.
 * <p>
 * Lives on the render thread only, like {@link UiState}: background threads report through
 * {@code runOnRenderThread}, and the tracker is never read from anywhere else. Not thread-safe.
 * <p>
 * Transitions are forgiving on purpose. A worker's late callback can arrive after the user has
 * cancelled the job, or after the job was dismissed; those calls are quietly ignored rather than
 * thrown, and a job never moves backwards — once cancelled, a later {@link #finish} cannot make
 * it look like it failed.
 */
public final class JobTracker {

    /**
     * How many jobs may run at once; the rest wait in the queue. The scheduler and the queued-row
     * text both read this, so the number is stated in one place.
     */
    public static final int CONCURRENCY = 2;

    /** Finished jobs kept for the view; beyond this the oldest finished job is dropped. */
    static final int MAX_FINISHED = 50;

    private final LongSupplier nanoClock;
    /** Keyed by id, which is also submission order, so iteration is oldest-submitted first. */
    private final Map<Long, Job> jobs = new LinkedHashMap<>();
    private long nextId = 1;

    public JobTracker() {
        this(System::nanoTime);
    }

    /** The clock is injected so tests can drive time; production uses {@link System#nanoTime()}. */
    public JobTracker(@NonNull LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** The tracker's notion of now, so the view and {@link #elapsed} agree with the timestamps. */
    public long now() {
        return nanoClock.getAsLong();
    }

    /** Registers a new job in the {@link Job.State#QUEUED} state. */
    public Job submit(String label, String command, String busyKey, @Nullable Runnable retry) {
        Job job = new Job(nextId++, label, command, busyKey, retry, now());
        jobs.put(job.id(), job);
        return job;
    }

    /** Moves a queued job to running. Ignored for anything else, including unknown ids. */
    public void start(long id) {
        Job job = jobs.get(id);
        if (job != null && job.state() == Job.State.QUEUED) {
            job.state(Job.State.RUNNING);
            job.startedAtNanos(now());
        }
    }

    /** Appends a line of output to the job, bounded to the newest {@link Job#MAX_OUTPUT}. */
    public void output(long id, String line) {
        Job job = jobs.get(id);
        if (job != null) {
            job.appendOutput(line);
        }
    }

    /**
     * Ends an active job as {@link Job.State#DONE} or {@link Job.State#FAILED}. A job that is
     * already finished — notably one the user cancelled — keeps its state, and the late result is
     * dropped.
     */
    public void finish(long id, boolean ok, @Nullable String note, @Nullable String error) {
        Job job = jobs.get(id);
        if (job == null || job.isFinished()) {
            return;
        }
        job.state(ok ? Job.State.DONE : Job.State.FAILED);
        job.finishedAtNanos(now());
        job.note(note);
        job.error(error);
        evictOldFinished();
    }

    /** Ends an active job as {@link Job.State#CANCELLED}. Ignored once the job has finished. */
    public void cancelled(long id) {
        Job job = jobs.get(id);
        if (job == null || job.isFinished()) {
            return;
        }
        job.state(Job.State.CANCELLED);
        job.finishedAtNanos(now());
        evictOldFinished();
    }

    public @Nullable Job get(long id) {
        return jobs.get(id);
    }

    /**
     * The queued or running job holding {@code busyKey}, or null. Used to tell whether an
     * operation is already pending before submitting a second copy of it. If several match, the
     * oldest wins.
     */
    public @Nullable Job activeFor(String busyKey) {
        for (Job job : jobs.values()) {
            if (job.isActive() && job.busyKey().equals(busyKey)) {
                return job;
            }
        }
        return null;
    }

    /**
     * Every job in the order the view lists them: running first (the one started longest ago at
     * the top), then queued (oldest first), then failed, then done and cancelled. The last two
     * groups put the most recent finish first, so what the user just did is nearest the top.
     */
    public List<Job> ordered() {
        List<Job> out = new ArrayList<>(jobs.values());
        out.sort(ORDER);
        return out;
    }

    private static final Comparator<Job> ORDER = Comparator
            .<Job>comparingInt(job -> rank(job.state()))
            .thenComparingLong(JobTracker::position)
            .thenComparingLong(Job::id);

    private static int rank(Job.State state) {
        return switch (state) {
            case RUNNING -> 0;
            case QUEUED -> 1;
            case FAILED -> 2;
            case DONE, CANCELLED -> 3;
        };
    }

    /** Within a group: ascending for running and queued, descending by finish time otherwise. */
    private static long position(Job job) {
        return switch (job.state()) {
            case RUNNING -> job.startedAtNanos();
            case QUEUED -> job.queuedAtNanos();
            default -> -job.finishedAtNanos();
        };
    }

    public int running() {
        return count(Job.State.RUNNING);
    }

    public int queued() {
        return count(Job.State.QUEUED);
    }

    /** Failed jobs the user has not dismissed yet; the header's failure count. */
    public int failed() {
        return count(Job.State.FAILED);
    }

    /**
     * Removes a finished job from the list. Active jobs cannot be dismissed — cancel them first —
     * so this is a no-op for them.
     */
    public void dismiss(long id) {
        Job job = jobs.get(id);
        if (job != null && job.isFinished()) {
            jobs.remove(id);
        }
    }

    private int count(Job.State state) {
        int n = 0;
        for (Job job : jobs.values()) {
            if (job.state() == state) {
                n++;
            }
        }
        return n;
    }

    /**
     * Keeps at most {@link #MAX_FINISHED} finished jobs by dropping the one that finished
     * earliest. Failed jobs count toward the cap like any other, so an unread failure can be
     * dropped once fifty newer jobs have finished.
     */
    private void evictOldFinished() {
        List<Job> finished = jobs.values().stream()
                .filter(Job::isFinished)
                .sorted(Comparator.comparingLong(Job::finishedAtNanos))
                .toList();
        int excess = finished.size() - MAX_FINISHED;
        for (int i = 0; i < excess; i++) {
            jobs.remove(finished.get(i).id());
        }
    }

    /**
     * How long the job has been going, as the view prints it: {@code 0.4s} under ten seconds,
     * {@code m:ss} from there. A job that never started is timed from when it was queued, so a
     * waiting job shows how long it has waited. A finished job stops at its finish time.
     */
    public static String elapsed(Job job, long nowNanos) {
        long start = job.startedAtNanos() != 0 ? job.startedAtNanos() : job.queuedAtNanos();
        long end = job.finishedAtNanos() != 0 ? job.finishedAtNanos() : nowNanos;
        double seconds = Math.max(0, end - start) / 1e9;
        // Round to tenths first, so 9.96s reads as 0:10 rather than the "10.0s" the wider form
        // would print, and the switch to m:ss happens exactly where the one-decimal form ends.
        long tenths = Math.round(seconds * 10);
        if (tenths < 100) {
            return String.format(Locale.ROOT, "%d.%ds", tenths / 10, tenths % 10);
        }
        long total = tenths / 10;
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }
}
