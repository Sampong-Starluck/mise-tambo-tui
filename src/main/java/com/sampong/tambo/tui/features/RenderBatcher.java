package com.sampong.tambo.tui.features;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Hands items from a background thread to the render thread in batches rather than one
 * {@code runOnRenderThread} hop per item.
 * <p>
 * Streamed command output arrives a line at a time, and a chatty build can produce thousands of
 * lines a second — each of which used to queue its own render-thread task, all to keep the last
 * 300 log lines. Here an item only schedules a drain when none is already pending; everything
 * that arrives before the render thread gets to it rides along in that same drain. Bursts
 * therefore cost one hop each, while a slow trickle still shows up immediately.
 * <p>
 * Thread-safe: {@link #accept} is called from the reader thread, {@code handler} runs on the
 * render thread only.
 */
final class RenderBatcher<T> implements Consumer<T> {

    private final Queue<T> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final Consumer<Runnable> renderThread;
    private final Consumer<T> handler;

    RenderBatcher(Consumer<Runnable> renderThread, Consumer<T> handler) {
        this.renderThread = renderThread;
        this.handler = handler;
    }

    @Override
    public void accept(T item) {
        pending.add(item);
        if (drainScheduled.compareAndSet(false, true)) {
            renderThread.accept(this::drain);
        }
    }

    private void drain() {
        // Cleared before polling, not after: an item added mid-drain then schedules its own
        // drain instead of being stranded in the queue until the next unrelated one.
        drainScheduled.set(false);
        T item;
        while ((item = pending.poll()) != null) {
            handler.accept(item);
        }
    }
}
