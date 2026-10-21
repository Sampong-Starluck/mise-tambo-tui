package com.sampong.tambo._common.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

class ConcurrentlyTest {

    private final AsyncTaskExecutor executor = new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());

    @Test
    void combinesBothAnswers() {
        String result = Concurrently.both(executor, () -> "ls", () -> 42, (a, b) -> a + ":" + b);

        assertThat(result).isEqualTo("ls:42");
    }

    @Test
    void runsBothAtTheSameTime() {
        // Each side waits for the other to start: this only finishes if they overlap.
        CountDownLatch bothStarted = new CountDownLatch(2);
        Boolean result = Concurrently.both(executor,
                () -> awaitPeer(bothStarted),
                () -> awaitPeer(bothStarted),
                (a, b) -> a && b);

        assertThat(result).isTrue();
    }

    private static boolean awaitPeer(CountDownLatch latch) {
        latch.countDown();
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void aFailureSurfacesAsTheOriginalException() {
        assertThatThrownBy(() -> Concurrently.both(executor,
                () -> {
                    throw new IllegalStateException("mise not found");
                },
                () -> 1,
                (a, b) -> b))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("mise not found");
    }
}
