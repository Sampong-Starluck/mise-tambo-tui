package com.sampong.tambo.tui.state;

/**
 * A value fetched on first use rather than at startup.
 * <p>
 * Panels trigger the fetch simply by rendering — {@link #claim()} makes that safe
 * from a render method that runs every frame, since only the first caller gets a
 * token and every later frame is a no-op until the value goes stale again.
 * <p>
 * Until the first load completes the holder reports the placeholder it was built
 * with and {@link #everLoaded()} stays false, so a panel can render "checking…"
 * instead of presenting a default as fact. After that the last loaded value is
 * kept across {@link #invalidate()}, so a refresh reloads in the background
 * without ever blanking a populated panel.
 * <p>
 * Not thread-safe: like {@link UiState}, only ever touched on the render thread.
 */
public final class Lazy<T> {

    /** Returned by {@link #claim()} when no load is needed — the value is fresh or already in flight. */
    public static final int NO_LOAD = -1;

    private final T placeholder;
    private T value;
    /** The value reflects a load that completed since the last {@link #invalidate()}. */
    private boolean fresh;
    private boolean loading;
    private boolean everLoaded;
    /**
     * Set when a load threw. Blocks further claims until the next
     * {@link #invalidate()}: since panels trigger loads from render, retrying
     * automatically would spawn a subprocess every frame for as long as the
     * failure lasted. The error goes to the command log and {@code r} retries.
     */
    private boolean failed;
    /**
     * Bumped whenever a load in flight is invalidated, so its late result can be
     * recognised as describing pre-invalidation state and dropped. Without this,
     * an {@code outdated} fetch started before an install would publish its
     * stale answer over the top of the post-install reload.
     */
    private int generation;

    public Lazy(T placeholder) {
        this.placeholder = placeholder;
        this.value = placeholder;
    }

    /** The placeholder until the first load completes, then the most recently loaded value. */
    public T value() {
        return value;
    }

    /** False until a load has completed at least once — the cue to render a "checking…" placeholder. */
    public boolean everLoaded() {
        return everLoaded;
    }

    /** True when the last attempt threw and nothing is being retried, so panels can say so. */
    public boolean failed() {
        return failed;
    }

    /**
     * Clears a recorded failure so the next claim tries again, leaving an
     * already-loaded value untouched. For gestures that imply "try that again",
     * such as reopening the modal whose data failed to load.
     */
    public void retryIfFailed() {
        this.failed = false;
    }

    /**
     * Claims the right to start a load, returning a token to hand back to
     * {@link #publish} or {@link #fail}, or {@link #NO_LOAD} when nothing should
     * be fetched.
     */
    public int claim() {
        if (fresh || loading || failed) {
            return NO_LOAD;
        }
        loading = true;
        return generation;
    }

    /** Publishes a completed load, ignoring one that was invalidated while in flight. */
    public void publish(int token, T loaded) {
        if (token != generation) {
            return;
        }
        this.value = loaded;
        this.fresh = true;
        this.loading = false;
        this.everLoaded = true;
        this.failed = false;
    }

    /**
     * Records that a load threw. The holder stops claiming until it is
     * invalidated, so a persistent failure cannot turn every frame into another
     * subprocess.
     */
    public void fail(int token) {
        if (token == generation) {
            this.loading = false;
            this.failed = true;
        }
    }

    /**
     * Marks the value stale so the next read reloads it, while keeping the
     * current value on screen in the meantime. Any load already in flight is
     * abandoned — it would answer for the state before whatever invalidated us.
     */
    public void invalidate() {
        this.fresh = false;
        this.failed = false;
        if (loading) {
            this.loading = false;
            this.generation++;
        }
    }

    /** Drops back to the placeholder entirely, as if nothing had ever been loaded. */
    public void reset() {
        invalidate();
        this.value = placeholder;
        this.everLoaded = false;
    }
}
