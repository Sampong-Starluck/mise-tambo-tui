package com.sampong.tambo.tui.panel;

import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.elements.Row;

import com.sampong.tambo.tui.LogEntry;
import com.sampong.tambo.tui.UiContext;

/**
 * The command log: every {@code mise} invocation this app makes, echoed the way
 * lazygit echoes its {@code git} calls. Sticky-scrolls to the newest entry.
 */
public final class LogPanel {

    private final UiContext ctx;

    public LogPanel(UiContext ctx) {
        this.ctx = ctx;
    }

    public ListElement<?> build() {
        ListElement<?> list = list()
                .title("Command Log")
                .rounded().borderColor(Color.DARK_GRAY)
                .displayOnly().stickyScroll();

        if (ctx.state().logEmpty()) {
            list.add(row(text("No commands run yet.").dim()));
        } else {
            for (LogEntry e : ctx.state().log()) {
                list.add(logRow(e));
            }
        }
        return list;
    }

    private Row logRow(LogEntry e) {
        return switch (e.level()) {
            case CMD -> row(text(e.text()).fg(Color.GRAY));
            case INFO -> row(text(e.text()).dim());
            case OK -> row(text(e.text()).fg(Color.GREEN));
            case ERROR -> row(text(e.text()).fg(Color.RED));
        };
    }
}
