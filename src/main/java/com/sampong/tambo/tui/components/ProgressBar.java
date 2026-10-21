package com.sampong.tambo.tui.components;

import org.jspecify.annotations.Nullable;

import com.sampong.tambo._common.model.InstallProgress;
import com.sampong.tambo.tui.state.ProgressSnapshot;

/**
 * The one download animation, drawn the same for every backend and on every surface that shows
 * an install — the Tools row, the detail pane, the header — from a {@link ProgressSnapshot}.
 * <p>
 * A phase with a known fraction draws a filled bar, smoothed to eighths of a cell and advanced
 * between readings at the rate the backend last reported. A phase without one (verifying,
 * unpacking, or a backend that gave no total) draws a block sliding back and forth along the
 * same track, so the row never looks frozen. Like {@link Ui#spinner()}, everything is derived
 * from the clock at render time rather than remembered between frames.
 */
public final class ProgressBar {

    private static final String FULL = "█";
    private static final String TRACK = "░";
    /** Left-aligned partial blocks, one to seven eighths of a cell. */
    private static final String[] EIGHTHS = {"", "▏", "▎", "▍", "▌", "▋", "▊", "▉"};
    private static final int SLIDER_WIDTH = 3;
    private static final long SLIDER_STEP_NANOS = 90_000_000L;

    /** Track width in a table row, where the status column is narrow. */
    public static final int ROW_WIDTH = 10;
    /** Track width in the header, which also carries the subject's name. */
    public static final int HEADER_WIDTH = 8;
    /** Track width in the detail pane, which has room to spare. */
    public static final int DETAIL_WIDTH = 24;

    private ProgressBar() {
    }

    /** {@code ███████▌░░  71%  3.4 MB/s}, or {@code ░░███░░░░░  extracting}. */
    public static String inline(ProgressSnapshot snapshot) {
        long now = System.nanoTime();
        Double fraction = snapshot.fractionAt(now);
        InstallProgress p = snapshot.progress();
        StringBuilder out = new StringBuilder(track(fraction, ROW_WIDTH, now));
        if (fraction != null) {
            out.append(' ').append(percent(fraction));
            if (p.speed() != null) {
                out.append("  ").append(p.speed());
            }
        } else {
            out.append("  ").append(p.phase().label());
        }
        return out.toString();
    }

    /** {@code node@22.11.0 ██████░░  71%}, for the single bar the header has room for. */
    public static String compact(ProgressSnapshot snapshot) {
        long now = System.nanoTime();
        Double fraction = snapshot.fractionAt(now);
        return snapshot.subject() + " " + track(fraction, HEADER_WIDTH, now) + " "
                + (fraction != null ? percent(fraction) : snapshot.progress().phase().label());
    }

    /** The wide bar for the detail pane, with the percentage but none of the figures. */
    public static String wide(ProgressSnapshot snapshot) {
        long now = System.nanoTime();
        Double fraction = snapshot.fractionAt(now);
        String bar = track(fraction, DETAIL_WIDTH, now);
        return fraction != null ? bar + " " + percent(fraction) : bar;
    }

    /**
     * {@code width} cells of bar: filled to {@code fraction} when there is one, otherwise a
     * slider whose position comes from the clock.
     */
    static String track(@Nullable Double fraction, int width, long nowNanos) {
        return fraction != null ? filled(fraction, width) : slider(width, nowNanos);
    }

    private static String filled(double fraction, int width) {
        int eighths = (int) Math.round(Math.clamp(fraction, 0.0, 1.0) * width * 8);
        int full = eighths / 8;
        int partial = eighths % 8;
        StringBuilder bar = new StringBuilder(width);
        bar.append(FULL.repeat(full));
        if (full < width) {
            bar.append(partial > 0 ? EIGHTHS[partial] : TRACK);
            bar.append(TRACK.repeat(width - full - 1));
        }
        return bar.toString();
    }

    private static String slider(int width, long nowNanos) {
        int travel = Math.max(1, width - SLIDER_WIDTH);
        int step = (int) ((nowNanos / SLIDER_STEP_NANOS) % (2L * travel));
        int at = step <= travel ? step : 2 * travel - step; // bounce off each end
        return TRACK.repeat(at) + FULL.repeat(SLIDER_WIDTH) + TRACK.repeat(width - at - SLIDER_WIDTH);
    }

    private static String percent(double fraction) {
        return String.format("%3d%%", (int) Math.floor(Math.clamp(fraction, 0.0, 1.0) * 100));
    }
}
