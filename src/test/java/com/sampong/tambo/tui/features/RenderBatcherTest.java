package com.sampong.tambo.tui.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

class RenderBatcherTest {

    /** A render thread we drive by hand: scheduled drains wait here until {@link #runAll()}. */
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Integer> delivered = new ArrayList<>();

    private void runAll() {
        while (!scheduled.isEmpty()) {
            scheduled.removeFirst().run();
        }
    }

    @Test
    void aBurstCostsOneRenderThreadHop() {
        RenderBatcher<Integer> batcher = new RenderBatcher<>(scheduled::add, delivered::add);

        for (int i = 0; i < 100; i++) {
            batcher.accept(i);
        }

        assertThat(scheduled).hasSize(1);
        runAll();
        assertThat(delivered).hasSize(100).isSortedAccordingTo(Integer::compare);
    }

    @Test
    void anItemAfterADrainSchedulesTheNextOne() {
        RenderBatcher<Integer> batcher = new RenderBatcher<>(scheduled::add, delivered::add);
        batcher.accept(1);
        runAll();

        batcher.accept(2);

        assertThat(scheduled).hasSize(1);
        runAll();
        assertThat(delivered).containsExactly(1, 2);
    }

    @Test
    void anItemArrivingMidDrainIsNotStranded() {
        List<RenderBatcher<Integer>> self = new ArrayList<>();
        RenderBatcher<Integer> batcher = new RenderBatcher<>(scheduled::add, item -> {
            delivered.add(item);
            if (item == 1) {
                self.getFirst().accept(99); // e.g. the reader thread racing the drain
            }
        });
        self.add(batcher);

        batcher.accept(1);
        batcher.accept(2);
        runAll();

        assertThat(delivered).containsExactly(1, 2, 99);
        assertThat(scheduled).isEmpty();
    }

    @Test
    void concurrentProducerLosesAndReordersNothing() throws Exception {
        ExecutorService render = Executors.newSingleThreadExecutor();
        AtomicInteger hops = new AtomicInteger();
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        Consumer<Runnable> renderThread = r -> {
            hops.incrementAndGet();
            render.execute(r);
        };
        RenderBatcher<Integer> batcher = new RenderBatcher<>(renderThread, seen::add);
        int n = 100_000;

        Thread reader = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < n; i++) {
                batcher.accept(i);
            }
        });
        reader.join();
        render.shutdown();
        assertThat(render.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(seen).hasSize(n).isSortedAccordingTo(Integer::compare);
        assertThat(hops.get()).isLessThan(n);
    }
}
