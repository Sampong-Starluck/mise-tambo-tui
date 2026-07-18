package com.sampong.tambo.tui.panel;

import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.List;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo.tui.PanelIds;
import com.sampong.tambo.tui.Ui;
import com.sampong.tambo.tui.UiContext;

/** Panel 2 — installed/configured tool versions with install/uninstall/global actions. */
public final class ToolsPanel {

    private final UiContext ctx;
    private int index;

    public ToolsPanel(UiContext ctx) {
        this.ctx = ctx;
    }

    /** The tool the selection sits on, or null when the list is empty. */
    public ToolVersion selected() {
        List<ToolVersion> items = ctx.state().tools();
        return items.isEmpty() ? null : items.get(Ui.clamp(index, items.size()));
    }

    public ListElement<?> build() {
        List<ToolVersion> items = ctx.state().tools();
        index = Ui.clamp(index, items.size());

        ListElement<?> list = list()
                .title(items.isEmpty() ? "2 Tools" : "2 Tools (" + items.size() + ")")
                .rounded().id(PanelIds.TOOLS).focusable(ctx.modalOpen())
                .borderColor(PanelIds.TOOLS.equals(ctx.focusedId()) ? Color.GREEN : Color.DARK_GRAY)
                .highlightColor(Color.CYAN)
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .selected(index)
                .onKeyEvent(event -> handleKey(event, items));

        if (items.isEmpty()) {
            list.add(row(text(ctx.state().loading()
                    ? "Loading…" : "No tools installed — press a to add one").dim()));
        } else {
            for (ToolVersion t : items) {
                list.add(toolRow(t));
            }
        }
        return list;
    }

    private Row toolRow(ToolVersion t) {
        boolean busy = ctx.state().isBusy(t.label());
        Color statusColor = t.active() ? Color.CYAN : t.installed() ? Color.GREEN : Color.DARK_GRAY;
        String badge = busy ? "…" : t.active() ? "●" : t.installed() ? "✓" : "○";
        String statusText = busy ? "working…" : t.installed() ? (t.active() ? "active" : "installed") : "not installed";
        return row(
                text(badge + " ").fg(statusColor),
                text(t.tool()).bold(),
                text("@" + t.version()),
                spacer(),
                text(statusText + " ").fg(statusColor).dim()
        );
    }

    private EventResult handleKey(KeyEvent event, List<ToolVersion> items) {
        if (Ui.isNavKey(event)) {
            index = Ui.applyNav(event, index, items.size());
            return EventResult.HANDLED;
        }
        if (items.isEmpty()) {
            return EventResult.UNHANDLED;
        }
        ToolVersion t = items.get(Ui.clamp(index, items.size()));
        if (event.isChar('i')) {
            ctx.actions().installTool(t);
            return EventResult.HANDLED;
        }
        if (event.isChar('u')) {
            // Apply to the project: writes tool@version into ./mise.toml
            ctx.actions().useTool(t.label(), false);
            return EventResult.HANDLED;
        }
        if (event.isChar('x') || event.code() == KeyCode.DELETE) {
            ctx.actions().uninstallTool(t);
            return EventResult.HANDLED;
        }
        if (event.isChar('g')) {
            ctx.actions().useTool(t.label(), true);
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }
}
