package com.sampong.tambo.tui.state;

import org.jspecify.annotations.Nullable;

import com.sampong.tambo._common.model.InstallProgress;

/**
 * The latest {@link InstallProgress} for one in-flight operation, plus when it arrived — enough
 * for the renderer to keep the bar moving between readings without keeping any state of its own.
 *
 * @param subject       what is being installed, for surfaces that show one bar for the whole
 *                      app (the header)
 * @param progress      the reading itself
 * @param baseFraction  where the bar starts from at {@code receivedAtNanos}: the reading's own
 *                      fraction, or the previously displayed one when that was already further
 *                      along, so a reading that lags the extrapolation pauses the bar instead of
 *                      pulling it backwards
 * @param receivedAtNanos {@link System#nanoTime()} when the reading was taken
 */
public record ProgressSnapshot(String subject, InstallProgress progress, @Nullable Double baseFraction,
                               long receivedAtNanos) {

    /**
     * Extrapolation never claims more than this; only a reading that actually says 100 %
     * fills the bar.
     */
    private static final double EXTRAPOLATION_CEILING = 0.99;

    /**
     * The tool part of {@link #subject}: {@code node} for {@code node@22.11.0}. Splits on the
     * last {@code @} after the first character, so a scoped name such as
     * {@code npm:@scope/pkg@1.0} keeps its own {@code @}.
     */
    public String toolName() {
        int at = subject.lastIndexOf('@');
        return at > 0 ? subject.substring(0, at) : subject;
    }

    /**
     * Completion to draw at {@code nowNanos}: the last reading, advanced at the rate it reported
     * if it reported one. Null when the current phase has no measurable extent.
     */
    public @Nullable Double fractionAt(long nowNanos) {
        if (baseFraction == null) {
            return null;
        }
        Double rate = progress.fractionPerSecond();
        if (rate == null || !(rate > 0) || baseFraction >= EXTRAPOLATION_CEILING) {
            return baseFraction;
        }
        double seconds = Math.max(0, nowNanos - receivedAtNanos) / 1e9;
        return Math.min(EXTRAPOLATION_CEILING, baseFraction + rate * seconds);
    }

    /**
     * The snapshot to replace this one with when {@code next} arrives. Within one phase the bar
     * only moves forward; a new phase starts from its own reading.
     */
    ProgressSnapshot advance(InstallProgress next, long nowNanos) {
        Double nextFraction = next.fraction();
        Double shown = fractionAt(nowNanos);
        // An if, not a ternary: `cond ? Math.max(..) : nextFraction` is typed double, which
        // unboxes a null nextFraction — the indeterminate phases — into a NullPointerException.
        Double base = nextFraction;
        if (next.phase() == progress.phase() && nextFraction != null && shown != null) {
            base = Math.max(nextFraction, shown);
        }
        return new ProgressSnapshot(subject, next, base, nowNanos);
    }

    static ProgressSnapshot first(String subject, InstallProgress progress, long nowNanos) {
        return new ProgressSnapshot(subject, progress, progress.fraction(), nowNanos);
    }
}
