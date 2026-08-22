package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo.tui.state.LogEntry;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Panel 5 — the command log: every {@code mise} invocation this app makes, echoed
 * the way lazygit echoes its {@code git} calls. Sticky-scrolls to the newest entry.
 * <p>
 * Focus it (click, or {@code 5}) and scroll: ↑/↓, PgUp/PgDn, Home vertically —
 * End resumes following the newest entry — and ←/→ or h/l pan long lines
 * horizontally. The mouse wheel scrolls vertically over the panel even when it
 * is not focused.
 */
@RequiredArgsConstructor
public final class LogPanel {

    /** Columns panned per ←/→ keypress. */
    private static final int H_STEP = 8;
    /** Rows scrolled per mouse wheel tick. */
    private static final int WHEEL_STEP = 3;

    @NonNull
    private final UiContext ctx;

    /**
     * The row that anchors the viewport, and whether it should keep tracking the
     * newest entry. Tracked here rather than via {@code ListElement.stickyScroll()}
     * because a fresh {@link ListElement} — and a fresh internal {@code ListState} —
     * is built every render, so anything the widget scrolls internally is discarded
     * before the next frame ever shows it.
     */
    private int index;
    private boolean followTail = true;

    public ListElement<?> build() {
        int offset = clampToLongestLine(ctx.state().logHScroll());
        boolean focused = PanelIds.LOG.equals(ctx.focusedId());
        List<LogEntry> entries = new ArrayList<>();
        ctx.state().log().forEach(entries::add);
        index = followTail ? entries.size() - 1 : Ui.clamp(index, entries.size());

        ListElement<?> list = list()
                .title("5 Command Log" + (offset > 0 ? "  →" + offset : ""))
                .rounded().id(PanelIds.LOG).focusable(ctx.modalOpen())
                .borderColor(focused ? ctx.theme().focus() : ctx.theme().idle())
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .displayOnly().autoScroll()
                .selected(index)
                .onKeyEvent(event -> {
                    if (event.code() == KeyCode.LEFT || event.isChar('h')) {
                        ctx.state().logHScroll(offset - H_STEP);
                        return EventResult.HANDLED;
                    }
                    if (event.code() == KeyCode.RIGHT || event.isChar('l')) {
                        ctx.state().logHScroll(offset + H_STEP);
                        return EventResult.HANDLED;
                    }
                    if (Ui.isNavKey(event)) {
                        index = Ui.applyNav(event, index, entries.size());
                        followTail = event.code() == KeyCode.END || index >= entries.size() - 1;
                        return EventResult.HANDLED;
                    }
                    return EventResult.UNHANDLED;
                })
                .onMouseEvent(event -> {
                    if (event.kind() == MouseEventKind.SCROLL_UP) {
                        index = Ui.clamp(index - WHEEL_STEP, entries.size());
                        followTail = false;
                        return EventResult.HANDLED;
                    }
                    if (event.kind() == MouseEventKind.SCROLL_DOWN) {
                        index = Ui.clamp(index + WHEEL_STEP, entries.size());
                        followTail = index >= entries.size() - 1;
                        return EventResult.HANDLED;
                    }
                    return EventResult.UNHANDLED;
                });

        if (entries.isEmpty()) {
            list.add(row(text("No commands run yet.").dim()));
        } else {
            for (LogEntry e : entries) {
                list.add(logRow(e, offset));
            }
        }
        return list;
    }

    /** Keeps the pan offset from running past the longest log line. */
    private int clampToLongestLine(int offset) {
        int longest = 0;
        for (LogEntry e : ctx.state().log()) {
            longest = Math.max(longest, e.text().length());
        }
        int clamped = Math.clamp(longest - 1, 0, offset);
        if (clamped != offset) {
            ctx.state().logHScroll(clamped);
        }
        return clamped;
    }

    private Row logRow(LogEntry e, int offset) {
        String line = offset < e.text().length() ? e.text().substring(offset) : "";
        return switch (e.level()) {
            case CMD -> row(text(line).fg(Color.CYAN).bold());
            case INFO -> infoRow(line);
            case OK -> row(text(line).fg(Color.GREEN).bold());
            case ERROR -> row(text(line).fg(Color.RED).bold());
        };
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
    private Row infoRow(String line) {
        Matcher m = SOURCE_TAG.matcher(line);
        String tag = "";
        String body = line;
        if (m.find()) {
            tag = m.group();
            body = line.substring(m.end());
        }
        Color highlight = classify(body);
        return tag.isEmpty()
                ? row(highlight != null ? text(body).fg(highlight) : text(body).dim())
                : row(text(tag).dim(), highlight != null ? text(body).fg(highlight) : text(body).dim());
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
