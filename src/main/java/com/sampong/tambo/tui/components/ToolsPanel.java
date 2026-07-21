package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.List;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo.mise.model.ToolVersion;
import com.sampong.tambo.tui.features.PanelFilter;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Panel 2 — installed/configured tool versions with install/uninstall/global actions. */
@RequiredArgsConstructor
public final class ToolsPanel {

    /**
     * Width of the trailing status column. Kept small: the sidebar is only 44
     * columns, and anything wider squeezes the flexible name column into
     * unreadability. Constant width (see {@code Ui.fixedWidth}) so a shorter
     * frame fully overwrites a longer one while the live status streams.
     */
    private static final int STATUS_WIDTH = 13;

    @NonNull
    private final UiContext ctx;
    private final PanelFilter filter = new PanelFilter(PanelIds.TOOLS_FILTER, PanelIds.TOOLS);
    private int index;
    private String lastQuery = "";
    /** Horizontal pan of the row text, in columns; 0 = no pan (←/→, h/l). */
    private int hScroll;

    /** The tools currently shown — the full list, or the fuzzy-filtered view. */
    private List<ToolVersion> visibleItems() {
        return filter.apply(ctx.state().tools(), ToolVersion::tool, ToolVersion::version);
    }

    /** The tool the selection sits on, or null when the (filtered) list is empty. */
    public @Nullable ToolVersion selected() {
        List<ToolVersion> items = visibleItems();
        return items.isEmpty() ? null : items.get(Ui.clamp(index, items.size()));
    }

    public Column build() {
        int total = ctx.state().tools().size();
        List<ToolVersion> items = visibleItems();

        String query = filter.query();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            index = 0;
        }
        index = Ui.clamp(index, items.size());

        ListElement<?> list = list()
                .title(title(total, items.size()))
                .rounded().id(PanelIds.TOOLS).focusable(ctx.modalOpen())
                .borderColor(PanelIds.TOOLS.equals(ctx.focusedId()) ? ctx.theme().focus() : ctx.theme().idle())
                .highlightColor(ctx.theme().accent())
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .autoScroll()
                .selected(index)
                .onKeyEvent(event -> handleKey(event, items));

        if (items.isEmpty()) {
            list.add(row(text(emptyText()).dim()));
        } else {
            for (ToolVersion t : items) {
                list.add(toolRow(t));
            }
        }

        if (filter.isActive()) {
            return column(filter.inputRow(ctx).constraint(length(1)), list.constraint(fill()));
        }
        return column(list.constraint(fill()));
    }

    private String title(int total, int shown) {
        if (total == 0) {
            return "2 Tools";
        }
        return filter.isActive() ? "2 Tools (" + shown + "/" + total + ")" : "2 Tools (" + total + ")";
    }

    private String emptyText() {
        if (filter.isActive()) {
            return "No tools match \"" + filter.query() + "\"";
        }
        return ctx.state().loading() ? "Loading…" : "No tools installed — press a to add one";
    }

    private Row toolRow(ToolVersion t) {
        boolean busy = ctx.state().isBusy(t.label()) || ctx.state().isBusy("upgrade:" + t.tool())
                || ctx.state().isBusy("registry:" + t.tool());
        Color statusColor = t.active() ? Color.CYAN : t.installed() ? Color.GREEN : Color.DARK_GRAY;
        String badge = busy ? Ui.spinner() : t.active() ? "●" : t.installed() ? "✓" : "○";
        String latest = ctx.state().outdated().get(t.tool());
        String statusText = busy ? busyText(t)
                : latest != null ? "↑ " + latest
                : t.installed() ? (t.active() ? "active" : "installed") : "not installed";
        Color statusTextColor = busy ? Color.YELLOW : latest != null ? Color.YELLOW : statusColor;
        return row(
                text(badge + " ").fg(statusColor),
                // One pannable string so ←/→ can reveal long names the narrow
                // sidebar clips (e.g. java@oracle-graalvm-25.0.3).
                text(Ui.pan(t.tool() + "@" + t.version(), hScroll)).bold(),
                spacer(),
                // Leading space guarantees a gap from the name even when the row
                // overflows and the spacer collapses to zero. Fixed width so a
                // shorter frame fully overwrites a longer previous one while the
                // live status streams (no ghosting).
                text(" " + Ui.fixedWidth(statusText, STATUS_WIDTH)).fg(statusTextColor).dim()
        );
    }

    /** The latest streamed line for whichever operation this tool is busy with, else "working…". */
    private String busyText(ToolVersion t) {
        for (String key : new String[]{t.label(), "upgrade:" + t.tool(), "registry:" + t.tool()}) {
            String status = ctx.state().busyStatusFor(key);
            if (status != null && !status.isBlank()) {
                return status;
            }
        }
        return "working…";
    }

    private EventResult handleKey(KeyEvent event, List<ToolVersion> items) {
        if (Ui.isNavKey(event)) {
            index = Ui.applyNav(event, index, items.size());
            return EventResult.HANDLED;
        }
        if (event.isChar('/')) {
            filter.activate(ctx);
            return EventResult.HANDLED;
        }
        if (event.isCancel() && filter.isActive()) {
            filter.clear(ctx);
            return EventResult.HANDLED;
        }
        if (Ui.isPanKey(event)) {
            hScroll = Ui.applyHPan(event, hScroll);
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
            if (t.installed()) {
                ctx.confirm("Uninstall " + t.label() + "?", () -> ctx.actions().uninstallTool(t));
            }
            return EventResult.HANDLED;
        }
        if (event.isChar('g')) {
            ctx.actions().useTool(t.label(), true);
            return EventResult.HANDLED;
        }
        if (event.isChar('p')) {
            ctx.actions().upgradeTool(t);
            return EventResult.HANDLED;
        }
        if (event.isChar('c')) {
            ctx.actions().cancelTool(t);
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }
}
