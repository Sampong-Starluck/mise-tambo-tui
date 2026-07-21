package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import org.jspecify.annotations.Nullable;

/** Small stateless rendering / navigation helpers shared by all panels. */
public final class Ui {

    private Ui() {
    }

    /** Clamps a selection index into {@code [0, size)}; returns 0 for empty lists. */
    public static int clamp(int i, int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(i, size - 1));
    }

    /** Returns true for the keys {@link #applyNav} knows how to handle. */
    public static boolean isNavKey(KeyEvent e) {
        return e.code() == KeyCode.UP || e.code() == KeyCode.DOWN || e.code() == KeyCode.HOME
                || e.code() == KeyCode.END || e.code() == KeyCode.PAGE_UP || e.code() == KeyCode.PAGE_DOWN
                || e.isChar('k') || e.isChar('j');
    }

    /** Applies list navigation (arrows, j/k, home/end, paging) to a selection index. */
    public static int applyNav(KeyEvent event, int current, int size) {
        if (size <= 0) {
            return 0;
        }
        if (event.code() == KeyCode.UP || event.isChar('k')) {
            return clamp(current - 1, size);
        }
        if (event.code() == KeyCode.DOWN || event.isChar('j')) {
            return clamp(current + 1, size);
        }
        if (event.code() == KeyCode.HOME) {
            return 0;
        }
        if (event.code() == KeyCode.END) {
            return size - 1;
        }
        if (event.code() == KeyCode.PAGE_UP) {
            return clamp(current - 10, size);
        }
        if (event.code() == KeyCode.PAGE_DOWN) {
            return clamp(current + 10, size);
        }
        return current;
    }

    /** Returns true for the keys {@link #applyHPan} knows how to handle: ←/→ and h/l. */
    public static boolean isPanKey(KeyEvent e) {
        return e.code() == KeyCode.LEFT || e.code() == KeyCode.RIGHT || e.isChar('h') || e.isChar('l');
    }

    /** Applies horizontal panning (←/→, h/l) to a column offset, 8 columns per step. */
    public static int applyHPan(KeyEvent event, int current) {
        if (event.code() == KeyCode.LEFT || event.isChar('h')) {
            return Math.max(0, current - 8);
        }
        if (event.code() == KeyCode.RIGHT || event.isChar('l')) {
            return Math.min(current + 8, 512);
        }
        return current;
    }

    /** Drops the first {@code offset} characters — the horizontal pan applied to row text. */
    public static String pan(@Nullable String s, int offset) {
        if (s == null) {
            return "";
        }
        if (offset <= 0) {
            return s;
        }
        return offset >= s.length() ? "" : s.substring(offset);
    }

    /** Renders a boolean as a colored yes/no badge. */
    public static Element badge(boolean value) {
        return value ? text("yes").fg(Color.GREEN) : text("no").fg(Color.DARK_GRAY);
    }

    private static final String[] SPINNER_FRAMES =
            {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    /**
     * A braille spinner frame chosen from the wall clock (~80ms per frame, the
     * cli-spinners convention for this glyph set). Stateless by design: the app
     * re-renders on every tick, and a fresh element tree is built each time, so
     * anything the panel tried to remember between frames would be discarded —
     * deriving the frame from {@code now} instead means there's nothing to lose.
     */
    public static String spinner() {
        int frame = (int) ((System.currentTimeMillis() / 80) % SPINNER_FRAMES.length);
        return SPINNER_FRAMES[frame];
    }

    public static String nullToDash(@Nullable String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    public static String truncate(@Nullable String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, Math.max(0, max - 1)) + "…" : s;
    }

    /**
     * Truncates (or right-pads with spaces) to exactly {@code width} characters.
     * Rows whose trailing text changes length every frame — a live streamed status
     * line, say — must render at a constant width, or a shorter frame can leave
     * stale characters from a longer previous one un-overwritten on terminals that
     * don't clear the full cell span on redraw.
     */
    public static String fixedWidth(@Nullable String s, int width) {
        String truncated = truncate(s, width);
        return truncated.length() >= width ? truncated : truncated + " ".repeat(width - truncated.length());
    }
}
