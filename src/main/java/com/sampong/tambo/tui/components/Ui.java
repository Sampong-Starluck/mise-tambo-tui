package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.table.TableState;

import org.jspecify.annotations.Nullable;

/**
 * Small stateless rendering / navigation helpers shared by all panels.
 * <p>
 * Smaller than it was: the table-backed panels let TamboUI own the viewport and the column
 * layout, so the hand-rolled horizontal panning and row padding they once needed are gone.
 * What remains is what no widget provides — clamping, word wrapping, and the list navigation
 * the two genuinely list-shaped surfaces (the command log and the Advanced menu) still use.
 */
public final class Ui {

    private Ui() {
    }

    /** Clamps a selection index into {@code [0, size)}; returns 0 for empty lists. */
    public static int clamp(int i, int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.clamp(i, 0, size - 1);
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

    /**
     * Drives a {@link TableState} from a navigation keypress, returning true when the key was
     * one. The table widget owns the viewport and keeps the cursor visible, so unlike
     * {@link #applyNav} this only has to move the selection — there is no offset to track and
     * no window to compute by hand.
     */
    public static boolean applyTableNav(KeyEvent event, TableState state, int size) {
        if (size <= 0) {
            return isNavKey(event);
        }
        int current = clamp(state.selected() == null ? 0 : state.selected(), size);
        if (event.code() == KeyCode.UP || event.isChar('k')) {
            state.select(clamp(current - 1, size));
        } else if (event.code() == KeyCode.DOWN || event.isChar('j')) {
            state.select(clamp(current + 1, size));
        } else if (event.code() == KeyCode.HOME) {
            state.select(0);
        } else if (event.code() == KeyCode.END) {
            state.select(size - 1);
        } else if (event.code() == KeyCode.PAGE_UP) {
            state.select(clamp(current - PAGE, size));
        } else if (event.code() == KeyCode.PAGE_DOWN) {
            state.select(clamp(current + PAGE, size));
        } else {
            return false;
        }
        return true;
    }

    /** Rows a page key moves by. */
    private static final int PAGE = 10;

    /** Renders a boolean as a colored yes/no badge. */
    public static Element badge(boolean value) {
        return value ? text("yes").fg(Color.GREEN) : text("no").fg(Color.DARK_GRAY);
    }

    /** A single "[key] label" fragment, key bolded/yellow — matches {@code HelpOverlay}'s hint style. */
    public static Element keyHint(String key, String label) {
        return row(text("[").dim(), text(key).bold().yellow(), text("] " + label).dim());
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
     * Rows whose trailing text changes length every frame — a live-streamed status
     * line, say — must render at a constant width, or a shorter frame can leave
     * stale characters from a longer previous one un-overwritten on terminals that
     * don't clear the full cell span on redraw.
     */
    public static String fixedWidth(@Nullable String s, int width) {
        String truncated = truncate(s, width);
        return truncated.length() >= width ? truncated : truncated + " ".repeat(width - truncated.length());
    }

    /**
     * Word-wraps {@code text} to at most {@code width} columns per line. Done by hand rather
     * than relying on TextElement's own {@code Overflow.WRAP_WORD} — that flag didn't actually
     * reflow multi-line content inside a list item / dialog in practice, so callers get back
     * plain lines they add as separate elements instead, which renders correctly everywhere
     * else in this app. A single word longer than {@code width} is kept whole rather than cut.
     */
    /**
     * Breaks {@code text} into {@code width}-column chunks at exactly the column, with no regard
     * for word boundaries. For values that are not prose and have no spaces to break on — an
     * environment variable's value above all — where {@link #wordWrap} would keep the whole
     * thing as one over-long "word" and let the renderer clip everything past the first line.
     */
    public static List<String> hardWrap(String text, int width) {
        if (width <= 0) {
            return List.of(text);
        }
        List<String> lines = new ArrayList<>();
        for (int at = 0; at < text.length(); at += width) {
            lines.add(text.substring(at, Math.min(text.length(), at + width)));
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    /**
     * Wraps a value that may be a {@code PATH}-style list: one entry per line when it is, since
     * that is how such a value is actually read, and a plain {@link #hardWrap} otherwise. An
     * entry longer than {@code width} is itself hard-wrapped rather than clipped.
     */
    public static List<String> wrapValue(String value, int width) {
        List<String> lines = new ArrayList<>();
        for (String part : value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            lines.addAll(hardWrap(part, width));
        }
        return lines;
    }

    public static List<String> wordWrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
