package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.tamboui.layout.Padding;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.ProjectTask;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo.tui.state.UiContext;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The main pane: everything about whatever row is selected in the focused side panel. lazygit's
 * right-hand pane, and the counterweight that lets {@link SidePanels} stay narrow — each list
 * over there carries only what identifies a row, because the rest of it is here.
 * <p>
 * It reads {@link SidePanels#focused()} rather than {@link UiContext#focusedId()} directly, which
 * is what keeps it showing the tool you were looking at while focus sits in the command log or a
 * filter box. A pane that blanked itself every time focus moved off the list would be useless
 * for the thing it is for: reading the detail while doing something else.
 */
@RequiredArgsConstructor
public final class DetailPanel {

    /** Columns of chrome to subtract before wrapping: the border, the padding, and the scrollbar. */
    private static final int CHROME_WIDTH = 6;
    /**
     * Width of the label column in the key/value rows below — one wider than the longest label
     * ("Shims on PATH"), so even that one keeps a space before its value.
     */
    private static final int LABEL_WIDTH = 15;
    /** Rows the mouse wheel scrolls the pane by, matching {@code LogPanel}. */
    private static final int WHEEL_STEP = 3;

    @NonNull
    private final UiContext ctx;
    @NonNull
    private final SidePanels sidePanels;
    @NonNull
    private final ToolsPanel toolsPanel;
    @NonNull
    private final TasksPanel tasksPanel;
    @NonNull
    private final EnvPanel envPanel;
    @NonNull
    private final AdvancedPanel advancedPanel;

    /**
     * First visible line of the pane, and the panel it belongs to. Reset when the stack moves to
     * a different panel: a scroll position from a long PATH means nothing in a three-line task
     * description, and leaving it would open the next panel part-way down.
     */
    private int scroll;
    private SidePanels.Side lastSide = SidePanels.Side.TOOLS;

    /**
     * {@code width} is the pane's rendered column count, used to wrap the long free text this
     * pane exists to show — a {@code PATH} value, a task's command line, an advanced action's
     * warning.
     */
    public Panel build(int width, List<AdvancedPanel.Action> advancedActions) {
        SidePanels.Side side = sidePanels.focused();
        int wrap = Math.max(20, width - CHROME_WIDTH);
        List<Element> lines = new ArrayList<>();
        String title;

        if (!sidePanels.available(side)) {
            title = side.title();
            addUnsupported(lines, side, wrap);
        } else {
            switch (side) {
                case STATUS -> {
                    title = ctx.backend().name();
                    addBackendReport(lines);
                }
                case TOOLS -> {
                    SdkVersion tool = toolsPanel.selected();
                    title = tool == null ? "Tools" : tool.name();
                    if (tool == null) {
                        addWelcome(lines);
                    } else {
                        addToolDetail(lines, tool);
                    }
                }
                case ENV -> {
                    Map.Entry<String, String> entry = envPanel.selected();
                    title = entry == null ? "Env" : entry.getKey();
                    if (entry == null) {
                        addEmpty(lines, "No environment variable selected.");
                    } else {
                        addEnvDetail(lines, entry, wrap);
                    }
                }
                case TASKS -> {
                    ProjectTask task = tasksPanel.selected();
                    title = task == null ? "Tasks" : task.name();
                    if (task == null) {
                        addEmpty(lines, "No task selected.");
                    } else {
                        addTaskDetail(lines, task, wrap);
                    }
                }
                case ADVANCED -> {
                    AdvancedPanel.Action action = advancedPanel.selected(advancedActions);
                    title = action == null ? "Advanced" : action.label();
                    if (action == null) {
                        addEmpty(lines, "Nothing to do here right now.");
                    } else {
                        addActionDetail(lines, action, wrap);
                    }
                }
                default -> {
                    title = side.title();
                    addWelcome(lines);
                }
            }
        }

        // A scrolling list rather than a plain column: a capability report, a PATH or a task's
        // script can all be taller than the pane, and a column would simply cut the rest off
        // with nothing on screen to say it had. The list's own scrollbar says so, and the wheel
        // works over the pane without focusing it — focus belongs to the stack, which is what
        // you are steering while you read this.
        if (side != lastSide) {
            lastSide = side;
            scroll = 0;
        }
        scroll = Ui.clamp(scroll, lines.size());

        ListElement<?> body = list()
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .displayOnly()
                .selected(scroll)
                .onMouseEvent(this::handleWheel);
        for (Element line : lines) {
            body.add((StyledElement<?>) line);
        }

        return panel(" " + title + " ", body.constraint(fill()))
                .rounded()
                .padding(Padding.symmetric(0, 1))
                .borderColor(ctx.theme().idle());
    }

    /** Wheel scrolling for the pane, which has no focus of its own to drive a key handler. */
    private EventResult handleWheel(MouseEvent event) {
        if (event.kind() == MouseEventKind.SCROLL_UP) {
            scroll = Math.max(0, scroll - WHEEL_STEP);
            return EventResult.HANDLED;
        }
        if (event.kind() == MouseEventKind.SCROLL_DOWN) {
            scroll += WHEEL_STEP;
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    // ==================== per-panel bodies ====================

    /**
     * What the backend is and what it can do. The capability list is the honest answer to a
     * question this app used to leave the user guessing at — whether a missing panel means the
     * backend has no such concept or the app failed to load it.
     */
    private void addBackendReport(List<Element> lines) {
        ctx.actions().ensureBackendInfo();
        String name = ctx.backend().name();
        boolean known = ctx.state().backendInfoLazy().everLoaded();
        BackendInfo info = ctx.state().backendInfo();

        lines.add(field("Backend", text(name).bold().cyan()));
        lines.add(field("Version", known ? text(info.version()) : text("checking…").dim()));
        lines.add(field("UI backend", text(ctx.uiBackend()).dim()));
        lines.add(field("Config file", text(ctx.backend().projectConfigFileName()).dim()));
        if (known && info.reportsHealth()) {
            lines.add(field("Activated", Ui.badge(info.activated())));
            lines.add(field("Shims on PATH", Ui.badge(info.shimsOnPath())));
            lines.add(field("Config files", text(String.valueOf(info.configFileCount()))));
        }
        lines.add(text(""));
        lines.add(text("What " + name + " can do here").bold());
        for (BackendFeature feature : BackendFeature.values()) {
            boolean supported = ctx.supports(feature);
            lines.add(row(
                    text(supported ? " ✓ " : " · ").fg(supported ? Color.GREEN : Color.DARK_GRAY),
                    supported ? text(feature.label()) : text(feature.label()).dim()));
        }
    }

    private void addToolDetail(List<Element> lines, SdkVersion t) {
        Color statusColor = t.active() ? Color.CYAN : t.installed() ? Color.GREEN : Color.DARK_GRAY;
        boolean pendingChange = t.requested() != null && !t.requested().isBlank()
                && !t.requested().equals(t.version());
        TextElement requested = text(Ui.nullToDash(t.requested()));
        if (pendingChange) {
            requested = requested.bold().yellow();
        }
        String latest = ctx.state().outdated().get(t.name());

        lines.add(field("Tool", text(t.name()).bold().cyan()));
        lines.add(field("Version", text(t.hasVersion() ? t.version() : "-").fg(statusColor)));
        lines.add(field("Requested", requested));
        lines.add(field("Installed", Ui.badge(t.installed())));
        lines.add(field("Active", Ui.badge(t.active())));
        lines.add(field("Source", text(Ui.nullToDash(t.sourceType())).dim()));
        lines.add(field("Install path", text(Ui.nullToDash(t.installPath())).dim()));
        if (latest != null) {
            lines.add(field("Upgrade", text("↑ " + latest + " available").yellow()));
        }
        if (pendingChange) {
            lines.add(text(""));
            lines.add(text(ctx.backend().projectConfigFileName() + " asks for "
                    + t.requested() + ", which is not what is installed.").yellow());
        }
        lines.add(text(""));
        lines.add(hints(
                Ui.keyHint("i", "install"),
                Ui.keyHint("u", "use in " + ctx.backend().projectConfigFileName()),
                Ui.keyHint("g", "set global")));
        lines.add(hints(
                Ui.keyHint("x", "uninstall"),
                Ui.keyHint("R", "remove from config"),
                Ui.keyHint("d", "remove plugin")));
    }

    /**
     * The whole value, wrapped. This is the pane's clearest justification: a {@code PATH} is
     * routinely several hundred characters, and every attempt to show it in the sidebar — a
     * clipped column, a sideways pan — was worse than simply giving it the room.
     */
    private void addEnvDetail(List<Element> lines, Map.Entry<String, String> entry, int wrap) {
        String value = entry.getValue();
        List<String> wrapped = Ui.wrapValue(value, wrap);
        lines.add(field("Variable", text(entry.getKey()).bold().yellow()));
        lines.add(field("Length", text(value.length() + " characters").dim()));
        // Only for a genuine list — a single value that merely wrapped onto several lines is
        // one entry, not several, and saying otherwise would be worse than saying nothing.
        int entries = value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)).length;
        if (entries > 1) {
            lines.add(field("Entries", text(entries + " path entries").dim()));
        }
        lines.add(text(""));
        for (String line : wrapped) {
            lines.add(text(line));
        }
        lines.add(text(""));
        lines.add(hints(Ui.keyHint("y", "copy value")));
    }

    private void addTaskDetail(List<Element> lines, ProjectTask t, int wrap) {
        boolean running = ctx.state().isBusy("task:" + t.name());
        lines.add(field("Task", text(t.name()).bold().cyan()));
        lines.add(field("Description", text(Ui.nullToDash(t.description()))));
        lines.add(field("Source", text(Ui.nullToDash(t.source())).dim()));
        lines.add(field("Aliases", text(t.aliasSummary()).dim()));
        lines.add(field("Depends on", text(t.dependsSummary()).dim()));
        if (running) {
            lines.add(field("State", text(Ui.spinner() + " running").yellow()));
        }
        lines.add(text(""));
        lines.add(text("Runs").bold());
        for (String line : Ui.wordWrap(t.runSummary(), wrap)) {
            lines.add(text(line).fg(Color.CYAN));
        }
        lines.add(text(""));
        lines.add(hints(
                Ui.keyHint("Enter", "run"),
                Ui.keyHint(":", "run with args"),
                Ui.keyHint(".", "re-run last"),
                Ui.keyHint("c", "cancel")));
    }

    private void addActionDetail(List<Element> lines, AdvancedPanel.Action action, int wrap) {
        lines.add(row(text(" " + action.key() + " ").bold().yellow(), text(action.label()).bold()));
        lines.add(text(""));
        for (String line : Ui.wordWrap(action.description(), wrap)) {
            lines.add(text(line).dim());
        }
        lines.add(text(""));
        lines.add(hints(Ui.keyHint("Enter", "run this"), Ui.keyHint(action.key(), "same, from anywhere")));
    }

    /** The backend's own explanation for a panel it cannot serve, rather than an empty pane. */
    private void addUnsupported(List<Element> lines, SidePanels.Side side, int wrap) {
        lines.add(text(side.title() + " is not available with " + ctx.backend().name())
                .bold().fg(Color.YELLOW));
        lines.add(text(""));
        for (String line : Ui.wordWrap(sidePanels.unsupportedReason(side), wrap)) {
            lines.add(text(line).dim());
        }
        lines.add(text(""));
        lines.add(text("The panel keeps its slot so the number keys mean the same thing "
                + "under either backend.").dim());
    }

    private void addEmpty(List<Element> lines, String message) {
        lines.add(text(message).dim());
    }

    private void addWelcome(List<Element> lines) {
        lines.add(text("tambo").bold().cyan());
        String backendName = ctx.backend().name();
        ctx.actions().ensureBackendInfo();
        lines.add(ctx.state().backendInfoLazy().everLoaded()
                ? text(backendName + " " + ctx.state().backendInfo().version()).fg(Color.GREEN)
                : text("checking " + backendName + "…").dim());
        lines.add(text(""));
        lines.add(row(text("Jump to a panel with ").dim(), text("1-5").bold().yellow(),
                text(", or ").dim(), text("Tab").bold().yellow(), text(" to cycle.").dim()));
        lines.add(row(text("Press ").dim(), text("a").bold().yellow(),
                text(" to fuzzy-find and install an SDK from the registry.").dim()));
        lines.add(row(text("Press ").dim(), text("?").bold().yellow(),
                text(" for the full key reference.").dim()));
    }

    // ==================== small builders ====================

    /** A {@code label   value} row, labels padded to one column so the values line up. */
    private static Element field(String label, Element value) {
        return row(text(Ui.fixedWidth(label, LABEL_WIDTH)).dim(), value);
    }

    /** A row of {@code [key] label} hints, spaced apart. */
    private static Element hints(Element... keyHints) {
        List<Element> parts = new ArrayList<>();
        for (Element hint : keyHints) {
            if (!parts.isEmpty()) {
                parts.add(text("   "));
            }
            parts.add(hint);
        }
        return row(parts.toArray(new Element[0]));
    }
}
