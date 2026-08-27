package com.sampong.tambo.tui.layout;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dock;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.stack;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import dev.tamboui.layout.Constraint;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;

import com.sampong.tambo.tui.TuiComponents;
import com.sampong.tambo.tui.components.AdvancedPanel;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Everything about what's on screen: the top-level dock (header/sidebar/main/footer), the
 * lazygit-style accordion the sidebar collapses into on cramped terminals, and the modal/overlay
 * stacking order. Turns {@link UiContext#state()} into {@link Element}s; owns no key handling of
 * its own — {@code com.sampong.tambo.tui.keys.GlobalKeyBindings} is the counterpart that decides
 * what a keypress does.
 */
@RequiredArgsConstructor
public final class AppLayout {

    private static final int SIDEBAR_WIDTH = 56;
    /** Below this terminal height the sidebar collapses unfocused panels to their title bar. */
    private static final int ACCORDION_HEIGHT = 28;
    /** A collapsed panel: just the top border with the title, plus the bottom border. */
    private static final int COLLAPSED_HEIGHT = 2;
    private static final int STATUS_HEIGHT = 8;
    /**
     * vfox's Status panel has far fewer rows than mise's (no doctor-derived active/trust/shims/
     * configs — see {@link com.sampong.tambo.tui.components.StatusPanel}'s vfox branch), so it
     * gets its own, shorter height rather than inheriting {@link #STATUS_HEIGHT} and leaving a
     * block of dead space below it. Covers the vfox/ui rows; +1 when offline adds the mode row.
     */
    private static final int VFOX_STATUS_HEIGHT = 4;
    /** Fixed width of the Advanced panel when split side-by-side with Details (V toggles it on). */
    private static final int ADVANCED_WIDTH = 60;

    @NonNull
    private final UiContext ctx;
    @NonNull
    private final TuiComponents ui;
    @NonNull
    private final IntSupplier terminalHeight;
    @NonNull
    private final Supplier<List<AdvancedPanel.Action>> advancedActions;

    public Element render() {
        if (ui.selectBackendModal().isOpen()) {
            // Nothing else is decided yet (buildHeader() alone would already call
            // actions.ensureDoctor(), firing a mise command before the user has even
            // chosen mise vs vfox) — show only the picker over a blank backdrop.
            return stack(text(""), ui.selectBackendModal().build());
        }

        Element body = dock()
                .top(buildHeader(), length(1))
                .bottom(buildFooter(), length(1))
                .center(row(
                        buildSidebar().constraint(length(SIDEBAR_WIDTH)),
                        buildMainColumn().constraint(fill())
                ));

        if (ui.helpOverlay().isOpen()) {
            return stack(body, ui.helpOverlay().build());
        }
        if (ui.registryModal().isOpen()) {
            return stack(body, ui.registryModal().build());
        }
        if (ui.configEditor().isOpen()) {
            return stack(body, ui.configEditor().build());
        }
        if (ui.confirmModal().isOpen()) {
            return stack(body, ui.confirmModal().build());
        }
        if (ui.switchBackendModal().isOpen()) {
            return stack(body, ui.switchBackendModal().build());
        }
        if (ui.taskArgsModal().isOpen()) {
            return stack(body, ui.taskArgsModal().build());
        }
        if (ui.addPluginModal().isOpen()) {
            return stack(body, ui.addPluginModal().build());
        }
        return body;
    }

    private Column buildSidebar() {
        // Env/Tasks are entirely mise-derived (env/tasks) with no vfox equivalent, so vfox
        // mode shows Status (now vfox-flavoured, see StatusPanel) plus Tools rather than four
        // panels of mostly nothing. The Advanced panel lives in the main column (see
        // buildMainColumn()), not here — it needs more room than this sidebar can spare once
        // terminals get cramped.
        if (ctx.state().vfox()) {
            int vfoxStatusHeight = VFOX_STATUS_HEIGHT + (ctx.state().offline() ? 1 : 0);
            if (terminalHeight.getAsInt() >= ACCORDION_HEIGHT) {
                return column(
                        ui.statusPanel().build().constraint(length(vfoxStatusHeight)),
                        ui.toolsPanel().build().constraint(fill())
                );
            }
            String focus = ctx.focusedId();
            String expandedId = PanelIds.STATUS.equals(focus) ? PanelIds.STATUS : PanelIds.TOOLS;
            return column(
                    ui.statusPanel().build().constraint(sidebarConstraint(expandedId, PanelIds.STATUS, length(vfoxStatusHeight))),
                    ui.toolsPanel().build().constraint(sidebarConstraint(expandedId, PanelIds.TOOLS, fill()))
            );
        }
        if (terminalHeight.getAsInt() >= ACCORDION_HEIGHT) {
            return column(
                    ui.statusPanel().build().constraint(length(STATUS_HEIGHT)),
                    ui.toolsPanel().build().constraint(fill(3)),
                    ui.envPanel().build().constraint(fill(1)),
                    ui.tasksPanel().build().constraint(fill(2))
            );
        }
        // lazygit-style accordion for cramped terminals: the focused panel gets all
        // the space, every other panel collapses to just its title bar.
        String focus = ctx.focusedId();
        String expandedId = switch (focus) {
            case PanelIds.STATUS, PanelIds.ENV, PanelIds.TASKS -> focus;
            case null, default -> PanelIds.TOOLS;
        };
        return column(
                ui.statusPanel().build().constraint(sidebarConstraint(expandedId, PanelIds.STATUS, length(STATUS_HEIGHT))),
                ui.toolsPanel().build().constraint(sidebarConstraint(expandedId, PanelIds.TOOLS, fill())),
                ui.envPanel().build().constraint(sidebarConstraint(expandedId, PanelIds.ENV, fill())),
                ui.tasksPanel().build().constraint(sidebarConstraint(expandedId, PanelIds.TASKS, fill()))
        );
    }

    private static Constraint sidebarConstraint(String expandedId, String panelId, Constraint whenExpanded) {
        return panelId.equals(expandedId) ? whenExpanded : length(COLLAPSED_HEIGHT);
    }

    /**
     * {@code V} splits Details side-by-side with Advanced (the interactive menu for the
     * maintenance/config actions {@code GlobalKeyBindings#requireAdvanced} gates); pressing it
     * again drops back to Details alone. Always available here regardless of terminal size or
     * vfox/mise mode, unlike the sidebar panels above, which run out of room on cramped terminals.
     */
    private Column buildMainColumn() {
        if (ctx.state().advancedFeatures()) {
            return column(
                    row(
                            ui.detailPanel().build().constraint(fill()),
                            ui.advancedPanel().build(advancedActions.get(), ADVANCED_WIDTH).constraint(length(ADVANCED_WIDTH))
                    ).constraint(fill(3)),
                    ui.logPanel().build().constraint(fill(1))
            );
        }
        return column(
                ui.detailPanel().build().constraint(fill(3)),
                ui.logPanel().build().constraint(fill(1))
        );
    }

    private Element buildHeader() {
        if (ctx.state().vfox()) {
            // No `vfox doctor` equivalent exists, but `vfox -v` gives at least a version badge.
            ctx.actions().ensureVfoxVersion();
            return row(
                    text(" tambo ").bold().cyan(),
                    text("— a TUI for vfox").dim(),
                    spacer(),
                    text("vfox " + ctx.state().vfoxVersion()).fg(Color.GREEN)
            );
        }
        // Both fields here come from `mise doctor`, which loads lazily; until it
        // answers the header stays neutral rather than announcing "not activated".
        ctx.actions().ensureDoctor();
        if (!ctx.state().doctorLazy().everLoaded()) {
            return row(
                    text(" tambo ").bold().cyan(),
                    text("— a TUI for mise").dim(),
                    spacer(),
                    text("checking mise…").dim()
            );
        }
        Color statusColor = ctx.state().doctor().activated() ? Color.GREEN : Color.YELLOW;
        String statusText = ctx.state().doctor().activated() ? "activated" : "not activated";
        return row(
                text(" tambo ").bold().cyan(),
                text("— a TUI for mise").dim(),
                spacer(),
                text("mise " + ctx.state().doctor().version() + "  ").dim(),
                text(statusText).fg(statusColor)
        );
    }

    private Element buildFooter() {
        String hints;
        if (ui.confirmModal().isOpen()) {
            hints = ui.confirmModal().footerHint();
        } else if (ui.switchBackendModal().isOpen()) {
            hints = ui.switchBackendModal().footerHint();
        } else if (ui.taskArgsModal().isOpen()) {
            hints = ui.taskArgsModal().footerHint();
        } else if (ui.addPluginModal().isOpen()) {
            hints = ui.addPluginModal().footerHint();
        } else if (ui.registryModal().isOpen()) {
            hints = ui.registryModal().footerHint();
        } else if (ui.configEditor().isOpen()) {
            hints = ui.configEditor().footerHint();
        } else {
            String focus = ctx.focusedId();
            hints = switch (focus) {
                case PanelIds.TOOLS -> ctx.state().vfox()
                        ? "↑/↓ select   / filter   ←/→ pan   i install   u use   x uninstall   R remove   g global   c cancel   C all"
                        : "↑/↓ select   / filter   ←/→ pan   i install   u use   x uninstall   g global   p upgrade   c cancel   C all";
                case PanelIds.TASKS -> "↑/↓ select   / filter   ←/→ pan   enter run   : args   . re-run   c cancel   C all";
                case PanelIds.ENV -> "↑/↓ scroll   / filter   ←/→ pan   y copy value";
                case PanelIds.LOG -> "↑/↓ j/k scroll   ←/→ h/l pan   PgUp/PgDn page   End follow newest";
                case PanelIds.ADVANCED -> "↑/↓ select   enter run   V hide";
                case null, default -> ctx.state().vfox()
                        ? "1,2,5 jump   tab cycle"
                        : (ctx.state().advancedFeatures() ? "1-6 jump   tab cycle" : "1-5 jump   tab cycle");
            };
        }
        return row(
                text(" " + hints).fg(Color.CYAN),
                spacer(),
                text(globalKeyHints()).dim()
        );
    }

    /**
     * The always-available, non-advanced keys. T/E/D/U/X/B (mise) and P/U (vfox) all require
     * {@code V} first, so they live in the Advanced menu instead of cluttering this default
     * hint line — {@code V} itself is the one thing here that points at them.
     */
    private String globalKeyHints() {
        if (ctx.state().vfox()) {
            return "a add   p add plugin   e edit   A activate   V advanced   r refresh   ? help   q quit ";
        }
        return "a add   e edit   A activate   P upgrade-all   V advanced   r refresh   ? help   q quit ";
    }
}
