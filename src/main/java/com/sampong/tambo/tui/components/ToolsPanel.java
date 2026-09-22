package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.table;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.layout.Alignment;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.TableElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.TableState;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo.tui.features.PanelFilter;
import com.sampong.tambo.tui.state.LogLevel;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Panel 2 — the installed and configured SDK versions, and the install / uninstall / pin actions
 * that act on them. The busiest panel in the app, and the one the sidebar's spare room goes to.
 * <p>
 * A {@link TableElement} rather than a plain list, for the reason lazygit pads its own list rows
 * into columns: a version that starts at a different column on every row is far harder to scan
 * than one that does not. It carries only what identifies a row — marker, name, version, what it
 * is doing — because everything else about the selection is one pane to the right in
 * {@link DetailPanel}, which is the whole point of the two-pane shape.
 * <p>
 * The table is deliberately borderless and wrapped in a {@link Panel}: the surrounding block is
 * what carries the focus colour, the numbered title, and the {@code n of m} counter on the bottom
 * border, none of which {@code TableElement}'s own block can express.
 */
@RequiredArgsConstructor
public final class ToolsPanel {

    @NonNull
    private final UiContext ctx;
    private final PanelFilter filter = new PanelFilter(PanelIds.TOOLS_FILTER, PanelIds.TOOLS);
    /**
     * Selection and viewport offset, held here rather than in the element: a fresh element tree
     * is built every frame, so anything the widget tracked internally would be discarded before
     * the next frame showed it.
     */
    private final TableState tableState = new TableState();
    private String lastQuery = "";

    /** The SDKs currently shown — the full list, or the fuzzy-filtered view. */
    private List<SdkVersion> visibleItems() {
        return filter.apply(ctx.state().sdks(), SdkVersion::name, SdkVersion::version);
    }

    /** The SDK the selection sits on, or null when the (filtered) list is empty. */
    public @Nullable SdkVersion selected() {
        List<SdkVersion> items = visibleItems();
        return items.isEmpty() ? null : items.get(selectedIndex(items.size()));
    }

    private int selectedIndex(int size) {
        Integer selected = tableState.selected();
        return Ui.clamp(selected == null ? 0 : selected, size);
    }

    public Panel build() {
        // The outdated check feeds nothing but the "↑ version" marker on a row, and it is the
        // slowest call in the app (network). Pulling it in from here lets the table paint from
        // the SDK listing alone and gain the markers later. It is a no-op on a backend that
        // cannot report outdated versions, so there is nothing to guard here.
        ctx.actions().ensureOutdated();
        int total = ctx.state().sdks().size();
        List<SdkVersion> items = visibleItems();

        String query = filter.query();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            tableState.select(0);
        }
        int index = selectedIndex(items.size());
        tableState.select(items.isEmpty() ? 0 : index);

        List<Row> rows = new ArrayList<>();
        for (SdkVersion sdk : items) {
            rows.add(toolRow(sdk));
        }

        TableElement tableElement = table()
                .widths(fill(3), length(12), fill(2))
                .rows(rows)
                .state(tableState)
                .highlightColor(ctx.theme().accent())
                .highlightSymbol("> ")
                .columnSpacing(1);

        if (items.isEmpty()) {
            tableElement.row(Row.from(Cell.from(emptyText()).style(Style.create().gray())));
        }

        Element body = filter.isActive()
                ? column(filter.inputRow(ctx).constraint(length(1)), tableElement.constraint(fill()))
                : column(tableElement.constraint(fill()));

        Panel block = panel(SidePanels.title(SidePanels.Side.TOOLS, countLabel(total, items.size())), body)
                .rounded()
                .id(PanelIds.TOOLS).focusable(ctx.modalOpen())
                .borderColor(ctx.theme().idle())
                .focusedBorderColor(ctx.theme().focus())
                .onKeyEvent(event -> handleKey(event, items));

        String position = SidePanels.positionLabel(index, items.size());
        if (!position.isEmpty() && PanelIds.TOOLS.equals(ctx.focusedId())) {
            block.bottomTitle(position).bottomTitleAlignment(Alignment.RIGHT);
        }
        return block;
    }

    /** The count that rides in the panel title: {@code (12)}, or {@code (3/12)} while filtering. */
    private String countLabel(int total, int shown) {
        if (total == 0) {
            return "";
        }
        return filter.isActive() ? "(" + shown + "/" + total + ")" : "(" + total + ")";
    }

    private String emptyText() {
        if (filter.isActive()) {
            return "No tools match \"" + filter.query() + "\"";
        }
        return ctx.state().sdksLazy().everLoaded()
                ? "No tools installed — press a to add one"
                : "Loading…";
    }

    /**
     * One table row. The status column carries the live streamed line while an operation runs,
     * which is what makes an install visible without opening the command log.
     */
    private Row toolRow(SdkVersion sdk) {
        boolean busy = isBusy(sdk);
        Color stateColor = sdk.active() ? Color.CYAN : sdk.installed() ? Color.GREEN : Color.DARK_GRAY;
        String badge = busy ? Ui.spinner() : sdk.active() ? "●" : sdk.installed() ? "✓" : "○";
        String latest = ctx.state().outdated().get(sdk.name());
        String statusText = busy ? busyText(sdk)
                : latest != null ? "↑ " + latest
                : !sdk.hasVersion() ? "no version"
                : sdk.active() ? "active" : sdk.installed() ? "" : "not installed";
        Color statusColor = busy || latest != null ? Color.YELLOW : stateColor;

        return Row.from(
                Cell.from(badge + " " + sdk.name()).style(Style.create().fg(stateColor)),
                Cell.from(sdk.hasVersion() ? sdk.version() : "-"),
                Cell.from(statusText).style(Style.create().fg(statusColor))
        );
    }

    private boolean isBusy(SdkVersion sdk) {
        return ctx.state().isBusy(sdk.label()) || ctx.state().isBusy("upgrade:" + sdk.name())
                || ctx.state().isBusy("registry:" + sdk.name())
                || ctx.state().isBusy("remove-plugin:" + sdk.name());
    }

    /** The latest streamed line for whichever operation this SDK is busy with, else "working…". */
    private String busyText(SdkVersion sdk) {
        for (String key : new String[]{sdk.label(), "upgrade:" + sdk.name(), "registry:" + sdk.name()}) {
            String status = ctx.state().busyStatusFor(key);
            if (status != null && !status.isBlank()) {
                return status;
            }
        }
        return "working…";
    }

    private EventResult handleKey(KeyEvent event, List<SdkVersion> items) {
        if (Ui.applyTableNav(event, tableState, items.size())) {
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
        if (items.isEmpty()) {
            return EventResult.UNHANDLED;
        }
        SdkVersion sdk = items.get(selectedIndex(items.size()));
        if (event.isChar('i')) {
            // An SDK registered with no version installed has no name@version to install, so
            // "install" means "pick a version" — the catalog modal's version step.
            if (sdk.hasVersion()) {
                ctx.actions().installSdk(sdk);
            } else {
                ctx.promptVersionFor(sdk.name());
            }
            return EventResult.HANDLED;
        }
        if (event.isChar('u')) {
            // Apply to the project: writes sdk@version into the project config
            if (sdk.hasVersion()) {
                ctx.actions().useSdk(sdk.label(), false);
            } else {
                ctx.state().addLog(LogLevel.INFO,
                        "No version of " + sdk.name() + " installed yet — press i to pick one");
            }
            return EventResult.HANDLED;
        }
        if (event.isChar('x') || event.code() == KeyCode.DELETE) {
            if (!ctx.state().advancedFeatures()) {
                ctx.state().addLog(LogLevel.INFO, "Uninstall is an advanced feature — press V to enable it");
            } else if (sdk.installed()) {
                ctx.confirm("Uninstall " + sdk.label() + "?", () -> ctx.actions().uninstallSdk(sdk));
            }
            return EventResult.HANDLED;
        }
        if (event.isChar('R')) {
            if (!ctx.state().advancedFeatures()) {
                ctx.state().addLog(LogLevel.INFO, "Remove from config is an advanced feature — press V to enable it");
            } else if (sdk.pinned()) {
                ctx.confirm("Remove " + sdk.label() + " from " + ctx.backend().projectConfigFileName() + "?",
                        () -> ctx.actions().removeSdk(sdk));
            }
            return EventResult.HANDLED;
        }
        if (event.isChar('d')) {
            if (!ctx.state().advancedFeatures()) {
                ctx.state().addLog(LogLevel.INFO, "Remove plugin is an advanced feature — press V to enable it");
            } else {
                // How destructive this is differs per backend, so the warning is the
                // backend's own sentence rather than a branch on which one is active.
                ctx.confirm(ctx.backend().removePluginWarning(sdk.name()),
                        () -> ctx.actions().removePlugin(sdk));
            }
            return EventResult.HANDLED;
        }
        if (event.isChar('g')) {
            if (sdk.hasVersion()) {
                ctx.actions().useSdk(sdk.label(), true);
            } else {
                ctx.state().addLog(LogLevel.INFO,
                        "No version of " + sdk.name() + " installed yet — press i to pick one");
            }
            return EventResult.HANDLED;
        }
        if (event.isChar('p') && ctx.supports(BackendFeature.UPGRADE)) {
            ctx.actions().upgradeSdk(sdk);
            return EventResult.HANDLED;
        }
        if (event.isChar('c')) {
            ctx.actions().cancelSdk(sdk);
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }
}
