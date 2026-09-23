package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo.tui.state.LogEntry;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The command log — every backend invocation this app makes, echoed
 * the way lazygit echoes its {@code git} calls. Sticky-scrolls to the newest entry.
 * <p>
 * Focus it (click, or {@code 5}) and scroll: ↑/↓, PgUp/PgDn, Home — End resumes
 * following the newest entry. The mouse wheel works over the panel even when it
 * is not focused.
 * <p>
 * Long lines — streamed build output above all — are word-wrapped to the panel's
 * width rather than clipped, so there is nothing to pan sideways. Each wrapped
 * line is its own list row, which is what the scroll position counts.
 */
@RequiredArgsConstructor
public final class LogPanel {

    /** Columns of chrome to subtract before wrapping: the border, the scrollbar, and a margin. */
    private static final int CHROME_WIDTH = 4;
    /** Rows scrolled per mouse wheel tick. */
    private static final int WHEEL_STEP = 3;

    @NonNull
    private final UiContext ctx;

    /**
     * The display row that anchors the viewport, and whether it should keep tracking the
     * newest entry. Tracked here rather than via {@code ListElement.stickyScroll()}
     * because a fresh {@link ListElement} — and a fresh internal {@code ListState} —
     * is built every render, so anything the widget scrolls internally is discarded
     * before the next frame ever shows it.
     */
    private int index;
    private boolean followTail = true;

    /** {@code width} is the panel's rendered column count, which the log lines are wrapped to. */
    public ListElement<?> build(int width) {
        boolean focused = PanelIds.LOG.equals(ctx.focusedId());
        int wrap = Math.max(20, width - CHROME_WIDTH);
        List<Element> rows = new ArrayList<>();
        for (LogEntry e : ctx.state().log()) {
            rows.addAll(logRows(e, wrap));
        }
        index = followTail ? rows.size() - 1 : Ui.clamp(index, rows.size());

        ListElement<?> list = list()
                .title(" [5] Command Log ")
                .rounded().id(PanelIds.LOG).focusable(ctx.modalOpen())
                .borderColor(focused ? ctx.theme().focus() : ctx.theme().idle())
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .displayOnly().autoScroll()
                .selected(index)
                .onKeyEvent(event -> handleKey(event, rows.size()))
                .onMouseEvent(event -> handleMouse(event, rows.size()));

        if (rows.isEmpty()) {
            list.add(row(text("No commands run yet.").dim()));
        }
        for (Element r : rows) {
            list.add((StyledElement<?>) r);
        }
        return list;
    }

    /** Keyboard scrolling; reaching the last row (or pressing End) resumes following the tail. */
    private EventResult handleKey(KeyEvent event, int rowCount) {
        if (!Ui.isNavKey(event)) {
            return EventResult.UNHANDLED;
        }
        index = Ui.applyNav(event, index, rowCount);
        followTail = event.code() == KeyCode.END || index >= rowCount - 1;
        return EventResult.HANDLED;
    }

    /** Wheel scrolling, which works over the panel whether or not it has focus. */
    private EventResult handleMouse(MouseEvent event, int rowCount) {
        if (event.kind() == MouseEventKind.SCROLL_UP) {
            index = Ui.clamp(index - WHEEL_STEP, rowCount);
            followTail = false;
            return EventResult.HANDLED;
        }
        if (event.kind() == MouseEventKind.SCROLL_DOWN) {
            index = Ui.clamp(index + WHEEL_STEP, rowCount);
            followTail = index >= rowCount - 1;
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    /** One entry, wrapped to {@code wrap} columns — one element per display row. */
    private List<Element> logRows(LogEntry e, int wrap) {
        return switch (e.level()) {
            case CMD -> plainRows(e.text(), wrap, t -> t.fg(Color.CYAN).bold());
            case INFO -> infoRows(e.text(), wrap);
            case OK -> plainRows(e.text(), wrap, t -> t.fg(Color.GREEN).bold());
            case ERROR -> plainRows(e.text(), wrap, t -> t.fg(Color.RED).bold());
        };
    }

    private static List<Element> plainRows(String line, int wrap, UnaryOperator<TextElement> style) {
        List<Element> rows = new ArrayList<>();
        for (String part : Ui.wordWrap(line, wrap)) {
            rows.add(row(style.apply(text(part))));
        }
        return rows;
    }

    /** Prefix {@code liveLogLine} tags every streamed subprocess line with, e.g. {@code "[compile] "}. */
    private static final Pattern SOURCE_TAG = Pattern.compile("^\\[[^]]+] ");

    /**
     * Streamed subprocess output (mvn, npm, cargo, gradle, …) arrives tagged but
     * otherwise as plain INFO text — any colour the subprocess itself applied is
     * long gone by the time it's captured line-by-line. Recover some of it by
     * recognising the conventions those tools use for their own errors/warnings/
     * success so the log still highlights what matters, the way running them
     * directly in a terminal would.
     */
    private List<Element> infoRows(String line, int wrap) {
        Matcher m = SOURCE_TAG.matcher(line);
        String tag = "";
        String body = line;
        if (m.find()) {
            tag = m.group();
            body = line.substring(m.end());
        }
        Color highlight = classify(body);
        UnaryOperator<TextElement> style = highlight != null ? t -> t.fg(highlight) : TextElement::dim;
        // Continuation lines hang under the body rather than the tag, so the tag column stays
        // readable down the log — unless the tag is so long that would leave no room for text.
        return tag.isEmpty() || tag.length() > wrap / 3
                ? plainRows(line, wrap, style)
                : Ui.hanging(text(tag).dim(), tag.length(), body, wrap, style);
    }

    /** Picks a highlight colour from build-tool-style markers in a streamed line, or none for plain output. */
    private static @Nullable Color classify(String body) {
        if (containsAny(body, "[ERROR]", "[FATAL]", "BUILD FAILURE", "BUILD FAILED", "npm ERR!", "error:")) {
            return Color.RED;
        }
        if (containsAny(body, "[WARN]", "[WARNING]", "npm WARN", "warning:")) {
            return Color.YELLOW;
        }
        if (containsAny(body, "BUILD SUCCESS", "BUILD SUCCESSFUL", "Build succeeded")) {
            return Color.GREEN;
        }
        return null;
    }

    private static boolean containsAny(String s, String... needles) {
        for (String needle : needles) {
            if (s.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
