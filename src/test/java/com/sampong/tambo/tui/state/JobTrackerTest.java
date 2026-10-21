package com.sampong.tambo.tui.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class JobTrackerTest {

    private static final long SECOND = 1_000_000_000L;

    /** A clock the test moves by hand. Starts well above 0, since 0 means "not yet" on a job. */
    private long now = 1_000 * SECOND;
    private final JobTracker jobs = new JobTracker(() -> now);

    private void advance(long nanos) {
        now += nanos;
    }

    private Job submit(String label) {
        return jobs.submit(label, "$ mise " + label, label, null);
    }

    @Test
    void submitQueuesAndStartRunsWithTimestamps() {
        Job job = submit("install node@23");

        assertThat(job.state()).isEqualTo(Job.State.QUEUED);
        assertThat(job.startedAtNanos()).isZero();
        assertThat(job.queuedAtNanos()).isEqualTo(now);

        advance(2 * SECOND);
        jobs.start(job.id());

        assertThat(job.state()).isEqualTo(Job.State.RUNNING);
        assertThat(job.startedAtNanos()).isEqualTo(now);
        assertThat(job.queuedAtNanos()).as("queue time is kept").isEqualTo(1_000 * SECOND);
    }

    @Test
    void finishOkMovesToDoneWithNote() {
        Job job = submit("upgrade node");
        jobs.start(job.id());
        jobs.output(job.id(), "downloading");
        advance(SECOND);
        jobs.finish(job.id(), true, "mise.toml updated", null);

        assertThat(job.state()).isEqualTo(Job.State.DONE);
        assertThat(job.note()).isEqualTo("mise.toml updated");
        assertThat(job.error()).isNull();
        assertThat(job.finishedAtNanos()).isEqualTo(now);
        assertThat(job.isFinished()).isTrue();
        assertThat(job.isActive()).isFalse();
    }

    @Test
    void finishFailedKeepsOneLineError() {
        Job job = submit("install python@3.14");
        jobs.start(job.id());
        jobs.output(job.id(), "ERROR download failed");
        jobs.finish(job.id(), false, null, "download failed: 404");

        assertThat(job.state()).isEqualTo(Job.State.FAILED);
        assertThat(job.error()).isEqualTo("download failed: 404");
        assertThat(job.lastOutput()).isEqualTo("ERROR download failed");
    }

    @Test
    void queuedJobCanFinishWithoutStarting() {
        Job job = submit("install go");
        jobs.finish(job.id(), false, null, "mise not found");

        assertThat(job.state()).isEqualTo(Job.State.FAILED);
        assertThat(job.startedAtNanos()).as("never started").isZero();
    }

    @Test
    void cancelledJobIgnoresTheLateResultOfItsWorker() {
        Job job = submit("install node");
        jobs.start(job.id());
        jobs.cancelled(job.id());
        advance(SECOND);
        // The killed process still reports back. It must not turn a cancel into a failure.
        jobs.finish(job.id(), false, null, "process exited 137");

        assertThat(job.state()).isEqualTo(Job.State.CANCELLED);
        assertThat(job.error()).isNull();
        assertThat(job.finishedAtNanos()).as("finish time is the cancel").isEqualTo(now - SECOND);
    }

    @Test
    void transitionsNeverGoBackwardsAndUnknownIdsAreIgnored() {
        Job job = submit("install node");
        jobs.start(job.id());
        jobs.start(job.id()); // already running
        jobs.finish(job.id(), true, null, null);
        jobs.start(job.id()); // already done
        jobs.cancelled(job.id()); // already done
        jobs.output(999, "nobody home");
        jobs.finish(999, true, null, null);
        jobs.cancelled(999);
        jobs.dismiss(999);

        assertThat(job.state()).isEqualTo(Job.State.DONE);
        assertThat(jobs.get(999)).isNull();
        assertThat(jobs.ordered()).containsExactly(job);
    }

    @Test
    void cancelOfAQueuedJobIsAllowed() {
        Job job = submit("install node");
        jobs.cancelled(job.id());

        assertThat(job.state()).isEqualTo(Job.State.CANCELLED);
    }

    @Test
    void outputIsBoundedToTheNewest500Lines() {
        Job job = submit("install node");
        for (int i = 0; i < 600; i++) {
            jobs.output(job.id(), "line " + i);
        }

        List<String> output = job.output();
        assertThat(output).hasSize(500);
        assertThat(output.getFirst()).isEqualTo("line 100");
        assertThat(output.getLast()).isEqualTo("line 599");
        assertThat(job.lastOutput()).isEqualTo("line 599");
    }

    @Test
    void outputIsACopyThatTheCallerCannotMutate() {
        Job job = submit("install node");
        jobs.output(job.id(), "one");

        List<String> copy = job.output();
        jobs.output(job.id(), "two");

        assertThat(copy).containsExactly("one");
        assertThat(job.output()).containsExactly("one", "two");
    }

    @Test
    void orderingPutsRunningThenQueuedThenFailedThenFinishedNewestFirst() {
        Job doneOld = submit("done-old");
        jobs.start(doneOld.id());
        advance(SECOND);
        jobs.finish(doneOld.id(), true, null, null);

        Job failed = submit("failed");
        jobs.start(failed.id());
        advance(SECOND);
        jobs.finish(failed.id(), false, null, "boom");

        Job cancelled = submit("cancelled");
        advance(SECOND);
        jobs.cancelled(cancelled.id());

        Job queuedFirst = submit("queued-first");
        advance(SECOND);
        Job queuedSecond = submit("queued-second");

        Job runOlder = submit("run-older");
        jobs.start(runOlder.id());
        advance(SECOND);
        Job runNewer = submit("run-newer");
        jobs.start(runNewer.id());

        List<Job> ordered = jobs.ordered();

        // Running: the one started longest ago first.
        assertThat(ordered).containsExactly(
                runOlder, runNewer,
                queuedFirst, queuedSecond,
                failed,
                cancelled, doneOld);
    }

    @Test
    void queuedJobsAreOldestFirstAndRunningOldestStartFirst() {
        Job a = submit("a");
        advance(SECOND);
        Job b = submit("b");
        jobs.start(b.id());
        advance(SECOND);
        jobs.start(a.id());

        assertThat(jobs.ordered()).containsExactly(b, a);
    }

    @Test
    void countsReportEachStateAndFailedDropsWhenDismissed() {
        Job running = submit("running");
        jobs.start(running.id());
        submit("queued-1");
        submit("queued-2");
        Job failed = submit("failed");
        jobs.finish(failed.id(), false, null, "x");
        Job done = submit("done");
        jobs.finish(done.id(), true, null, null);

        assertThat(jobs.running()).isEqualTo(1);
        assertThat(jobs.queued()).isEqualTo(2);
        assertThat(jobs.failed()).isEqualTo(1);

        jobs.dismiss(failed.id());

        assertThat(jobs.failed()).isZero();
        assertThat(jobs.get(failed.id())).isNull();
    }

    @Test
    void dismissRemovesOnlyFinishedJobs() {
        Job running = submit("running");
        jobs.start(running.id());
        jobs.dismiss(running.id());

        assertThat(jobs.get(running.id())).as("an active job cannot be dismissed").isSameAs(running);
        assertThat(jobs.running()).isEqualTo(1);
    }

    @Test
    void activeForFindsOnlyQueuedOrRunningJobsWithTheKey() {
        Job node = jobs.submit("install node", "$ mise install node", "node@23", null);
        assertThat(jobs.activeFor("node@23")).isSameAs(node);
        assertThat(jobs.activeFor("python@3")).isNull();

        jobs.start(node.id());
        assertThat(jobs.activeFor("node@23")).isSameAs(node);

        jobs.finish(node.id(), true, null, null);
        assertThat(jobs.activeFor("node@23")).as("finished jobs do not hold the key").isNull();
    }

    @Test
    void activeForReturnsTheOldestWhenSeveralMatch() {
        Job first = jobs.submit("a", "$ a", "same", null);
        jobs.submit("b", "$ b", "same", null);

        assertThat(jobs.activeFor("same")).isSameAs(first);
    }

    @Test
    void finishedJobsAreCappedAt50DroppingTheOldestFinished() {
        List<Job> submitted = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Job job = submit("job-" + i);
            submitted.add(job);
            jobs.start(job.id());
            advance(SECOND);
            jobs.finish(job.id(), true, null, null);
        }

        int finished = 0;
        for (Job job : jobs.ordered()) {
            if (job.isFinished()) {
                finished++;
            }
        }
        assertThat(finished).isEqualTo(50);
        // The five that finished first are gone; the rest are still there.
        for (int i = 0; i < 5; i++) {
            assertThat(jobs.get(submitted.get(i).id())).as("job %d evicted", i).isNull();
        }
        assertThat(jobs.get(submitted.get(5).id())).isNotNull();
    }

    @Test
    void activeJobsAreNeverEvictedByTheFinishedCap() {
        Job running = submit("long install");
        jobs.start(running.id());
        for (int i = 0; i < 60; i++) {
            Job job = submit("quick-" + i);
            jobs.finish(job.id(), true, null, null);
        }

        assertThat(jobs.get(running.id())).isSameAs(running);
        assertThat(running.state()).isEqualTo(Job.State.RUNNING);
    }

    @Test
    void retryIsKeptAsGivenAndNullWhenAbsent() {
        Runnable again = () -> {
        };
        Job withRetry = jobs.submit("install node", "$ mise install node", "node", again);
        Job without = submit("install go");

        assertThat(withRetry.retry()).isSameAs(again);
        assertThat(without.retry()).isNull();
    }

    @Test
    void elapsedUnderTenSecondsHasOneDecimal() {
        Job job = submit("install node");
        jobs.start(job.id());

        assertThat(JobTracker.elapsed(job, job.startedAtNanos() + 400_000_000L)).isEqualTo("0.4s");
        assertThat(JobTracker.elapsed(job, job.startedAtNanos())).isEqualTo("0.0s");
        assertThat(JobTracker.elapsed(job, job.startedAtNanos() + 9_400_000_000L)).isEqualTo("9.4s");
    }

    @Test
    void elapsedFromTenSecondsIsMinutesAndSeconds() {
        Job job = submit("install node");
        jobs.start(job.id());
        long start = job.startedAtNanos();

        assertThat(JobTracker.elapsed(job, start + 10 * SECOND)).isEqualTo("0:10");
        assertThat(JobTracker.elapsed(job, start + 65 * SECOND)).isEqualTo("1:05");
        assertThat(JobTracker.elapsed(job, start + 600 * SECOND)).isEqualTo("10:00");
    }

    @Test
    void elapsedRoundsNinePointNineSixUpIntoTheMinuteForm() {
        Job job = submit("install node");
        jobs.start(job.id());

        // 9.96s rounds to 10.0, which is past the one-decimal range, so it reads as 0:10 rather
        // than the "10.0s" the one-decimal form would otherwise print.
        assertThat(JobTracker.elapsed(job, job.startedAtNanos() + 9_960_000_000L)).isEqualTo("0:10");
    }

    @Test
    void elapsedStopsAtTheFinishTime() {
        Job job = submit("install node");
        jobs.start(job.id());
        advance(3 * SECOND);
        jobs.finish(job.id(), true, null, null);
        advance(60 * SECOND);

        assertThat(JobTracker.elapsed(job, now)).isEqualTo("3.0s");
    }

    @Test
    void elapsedOfAQueuedJobIsItsWaitTime() {
        Job job = submit("install node");
        advance(4 * SECOND);

        assertThat(JobTracker.elapsed(job, now)).isEqualTo("4.0s");
    }
}
