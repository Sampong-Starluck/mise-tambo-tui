package com.sampong.tambo.tui;

import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

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

    public static String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, Math.max(0, max - 1)) + "…" : s;
    }
}
