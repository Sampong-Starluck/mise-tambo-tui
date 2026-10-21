package com.sampong.tambo._common.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sampong.tambo.testsupport.FakeCli;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CancelRegistryTest {

    private final CancelRegistry registry = new CancelRegistry();
    private final List<Process> started = new ArrayList<>();

    @AfterEach
    void killStragglers() {
        started.forEach(Process::destroyForcibly);
    }

    private Process sleeper() throws Exception {
        Process p = FakeCli.start("sleep", "60000");
        started.add(p);
        return p;
    }

    private static boolean exits(Process p) throws InterruptedException {
        return p.waitFor(15, TimeUnit.SECONDS);
    }

    @Test
    void cancelKillsTheRegisteredProcess() throws Exception {
        Process p = sleeper();
        registry.register("task:dev", p);

        assertThat(registry.isRunning("task:dev")).isTrue();
        assertThat(registry.cancel("task:dev")).isTrue();

        assertThat(exits(p)).isTrue();
        assertThat(registry.isRunning("task:dev")).isFalse();
        assertThat(registry.cancel("task:dev")).as("already gone").isFalse();
    }

    @Test
    void cancellingAnUnknownKeyIsANoOp() {
        assertThat(registry.cancel("nothing")).isFalse();
        assertThat(registry.cancelAll()).isEmpty();
    }

    @Test
    void cancelAllReportsWhatItKilled() throws Exception {
        Process a = sleeper();
        Process b = sleeper();
        registry.register("task:a", a);
        registry.register("node@22", b);

        assertThat(registry.runningKeys()).containsExactlyInAnyOrder("task:a", "node@22");
        assertThat(registry.cancelAll()).containsExactlyInAnyOrder("task:a", "node@22");

        assertThat(exits(a)).isTrue();
        assertThat(exits(b)).isTrue();
        assertThat(registry.runningKeys()).isEmpty();
    }

    @Test
    void shutdownKillsKeyedAndUnkeyedProcesses() throws Exception {
        Process keyed = sleeper();
        Process unkeyed = sleeper();
        registry.register("task:dev", keyed);
        registry.track(unkeyed);

        registry.destroy();

        assertThat(exits(keyed)).isTrue();
        assertThat(exits(unkeyed)).isTrue();
    }

    @Test
    void anUntrackedProcessIsLeftAlone() throws Exception {
        Process p = sleeper();
        registry.track(p);
        registry.untrack(p);

        registry.destroy();

        assertThat(p.waitFor(500, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void untrackedProcessesAreNotUserCancellable() throws Exception {
        registry.track(sleeper());

        assertThat(registry.runningKeys()).isEmpty();
        assertThat(registry.cancelAll()).isEmpty();
    }
}
