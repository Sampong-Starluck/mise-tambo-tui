package com.sampong.tambo.tui.layout;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dock;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.stack;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.util.AppVersion;
import com.sampong.tambo.tui.TuiComponents;
import com.sampong.tambo.tui.components.AdvancedPanel;
import com.sampong.tambo.tui.components.SidePanels;
import com.sampong.tambo.tui.components.Ui;
import com.sampong.tambo.tui.state.UiContext;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Everything about what's on screen: the header, the lazygit-style panel stack down the left, the
 * main pane and command log beside it, the footer, and the modal/overlay stacking order. Turns
 * {@link UiContext#state()} into {@link Element}s; owns no key handling of its own —
 * {@code com.sampong.tambo.tui.keys.GlobalKeyBindings} is the counterpart that decides what a
 * keypress does.
 * <p>
 * The shape is lazygit's, and it is deliberately back: a stack of small numbered panels on the
 * left, one large pane on the right showing whatever the selection is, a command log under it,
 * and a single contextual hint line at the bottom. An interim version showed one panel at a time
 * in a tab bar, which did give the active list the full width — at the cost of the property this
 * layout exists for, that an install running in Tools stays visible while you read Tasks.
 * <p>
 * Nothing here branches on which backend is active. The stack decides for itself which panels a
 * backend can serve and how tall each one is (see {@link SidePanels}), so a backend with fewer
 * panels gets bigger ones rather than a column of dead space.
 */
@RequiredArgsConstructor
public final class AppLayout {

    /** The header and footer lines the body sits between. */
    private static final int HEADER_FOOTER_ROWS = 2;
    /**
     * Below this terminal width the main pane is dropped and the stack takes the whole width.
     * Splitting a narrow terminal in two leaves a sidebar too cramped to show a version number
     * beside a name, and a pane too cramped to be worth the columns it cost.
     */
    private static final int TWO_PANE_MIN_WIDTH = 96;
    /** Share of the width the sidebar asks for, before the min/max clamp. */
    private static final int SIDEBAR_PERCENT = 36;
    private static final int SIDEBAR_MIN_WIDTH = 42;
    private static final int SIDEBAR_MAX_WIDTH = 72;
    /** Assumed terminal size when the backend cannot report one — a conventional 80x24 and a bit. */
    private static final int ASSUMED_WIDTH = 120;
    private static final int ASSUMED_HEIGHT = 40;
    /** Vertical split between the main pane and the command log beneath it. */
    private static final int MAIN_WEIGHT = 3;
    private static final int LOG_WEIGHT = 1;
    /** Columns kept clear between the footer's two halves so they never read as one sentence. */
    private static final int FOOTER_GAP = 3;

    @NonNull
    private final UiContext ctx;
    @NonNull
    private final TuiComponents ui;
    @NonNull
    private final IntSupplier terminalHeight;
    @NonNull
    private final IntSupplier terminalWidth;
    @NonNull
    private final Supplier<List<AdvancedPanel.Action>> advancedActions;

    public Element render() {
        if (ui.selectBackendModal().isOpen()) {
            // Nothing else is decided yet (buildHeader() alone would already ask the backend
            // for its version, firing a command before the user has even chosen mise vs vfox)
            // — show only the picker over a blank backdrop.
            return stack(text(""), ui.selectBackendModal().build());
        }

        Element body = dock()
                .top(buildHeader(), length(1))
                .bottom(buildFooter(), length(1))
                .center(buildBody());

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
        if (ui.autoInstallModal().isOpen()) {
            return stack(body, ui.autoInstallModal().build());
        }
        return body;
    }

    /**
     * The stack beside the main pane and the command log — or, on a terminal too narrow to
     * split, the stack above the log with the main pane dropped. The main pane is what goes
     * first: everything it shows is <em>about</em> a row in the stack, so losing it costs
     * detail, while losing the stack would cost the thing being detailed.
     */
    private Element buildBody() {
        int width = size(terminalWidth, ASSUMED_WIDTH);
        int bodyHeight = Math.max(6, size(terminalHeight, ASSUMED_HEIGHT) - HEADER_FOOTER_ROWS);
        List<AdvancedPanel.Action> actions = advancedActions.get();

        if (width < TWO_PANE_MIN_WIDTH) {
            int stackHeight = bodyHeight * MAIN_WEIGHT / (MAIN_WEIGHT + LOG_WEIGHT);
            return column(
                    buildSidebar(stackHeight, actions).constraint(fill(MAIN_WEIGHT)),
                    ui.logPanel().build(width).constraint(fill(LOG_WEIGHT)));
        }

        int sidebarWidth = Math.clamp(width * SIDEBAR_PERCENT / 100L,
                SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH);
        return row(
                buildSidebar(bodyHeight, actions).constraint(length(sidebarWidth)),
                column(
                        ui.detailPanel().build(width - sidebarWidth, actions).constraint(fill(MAIN_WEIGHT)),
                        ui.logPanel().build(width - sidebarWidth).constraint(fill(LOG_WEIGHT))
                ).constraint(fill()));
    }

    /** The numbered panel stack, each panel sized by {@link SidePanels#constraint}. */
    private StyledElement<?> buildSidebar(int sidebarHeight, List<AdvancedPanel.Action> actions) {
        SidePanels sides = ui.sidePanels();
        List<Element> stacked = new ArrayList<>();
        for (SidePanels.Side side : sides.visible()) {
            stacked.add(buildSide(side, actions).constraint(sides.constraint(side, sidebarHeight)));
        }
        return column(stacked.toArray(new Element[0]));
    }

    /**
     * One panel of the stack. A panel the backend cannot serve still renders — collapsed to its
     * title bar, dimmed, and not focusable — so the stack keeps the same slots and the same
     * number keys whichever backend is driving. The explanation goes in the main pane, which is
     * the only place with room for a sentence.
     */
    private StyledElement<?> buildSide(SidePanels.Side side, List<AdvancedPanel.Action> actions) {
        SidePanels sides = ui.sidePanels();
        if (!sides.available(side)) {
            return panel(SidePanels.unavailableTitle(side))
                    .rounded()
                    .borderColor(ctx.theme().idle());
        }
        return switch (side) {
            case STATUS -> ui.statusPanel().build();
            case TOOLS -> ui.toolsPanel().build();
            case ENV -> ui.envPanel().build();
            case TASKS -> ui.tasksPanel().build();
            case ADVANCED -> ui.advancedPanel().build(actions);
        };
    }

    /** Falls back to an assumed size when the terminal cannot report one (it returns MAX_VALUE). */
    private static int size(IntSupplier source, int assumed) {
        int reported = source.getAsInt();
        return reported <= 0 || reported == Integer.MAX_VALUE ? assumed : reported;
    }

    /**
     * Name, version, and — for a backend that reports health — whether it is activated, plus a
     * live count of what is running. The activity counter earns its place even with the stack
     * visible: a panel collapsed to its title bar on a short terminal hides its own spinners.
     */
    private Element buildHeader() {
        String name = ctx.backend().name();
        ctx.actions().ensureBackendInfo();
        boolean known = ctx.state().backendInfoLazy().everLoaded();

        Element activity = ctx.state().anyBusy()
                ? text(Ui.spinner() + " " + ctx.state().busyCount() + " running  ").fg(Color.YELLOW)
                : text("");

        if (!ctx.supports(BackendFeature.DOCTOR)) {
            // No health report behind this backend — its version is all there is to badge.
            return row(
                    text(" tambo ").bold().cyan(),
                    appVersion(),
                    text("— a TUI for " + name).dim(),
                    spacer(),
                    activity,
                    known ? text(name + " " + ctx.state().backendInfo().version()).fg(Color.GREEN)
                            : text("checking " + name + "…").dim()
            );
        }
        if (!known) {
            return row(
                    text(" tambo ").bold().cyan(),
                    appVersion(),
                    text("— a TUI for " + name).dim(),
                    spacer(),
                    activity,
                    text("checking " + name + "…").dim()
            );
        }
        boolean activated = ctx.state().backendInfo().activated();
        return row(
                text(" tambo ").bold().cyan(),
                appVersion(),
                text("— a TUI for " + name).dim(),
                spacer(),
                activity,
                text(name + " " + ctx.state().backendInfo().version() + "  ").dim(),
                text(activated ? "activated" : "not activated").fg(activated ? Color.GREEN : Color.YELLOW)
        );
    }

    /**
     * tambo's own version beside its name — the same string {@code --version} prints, so a
     * screenshot of the header says which build it came from.
     */
    private static Element appVersion() {
        String version = AppVersion.get();
        return text((AppVersion.DEVELOPMENT.equals(version) ? "dev" : "v" + version) + " ").cyan();
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
        } else if (ui.autoInstallModal().isOpen()) {
            hints = ui.autoInstallModal().footerHint();
        } else if (ui.registryModal().isOpen()) {
            hints = ui.registryModal().footerHint();
        } else if (ui.configEditor().isOpen()) {
            hints = ui.configEditor().footerHint();
        } else {
            hints = panelHints();
        }
        // Both halves are as long as they are useful, which on a narrow terminal is longer than
        // the line. Rather than let them collide in the middle — which is what a spacer between
        // two oversized strings does — the global half drops first, then the contextual half is
        // cut. Everything dropped is still one '?' away, which is the whole point of having a
        // short hint line and a full reference.
        String global = globalKeyHints();
        int width = size(terminalWidth, ASSUMED_WIDTH);
        if (hints.length() + global.length() + FOOTER_GAP > width) {
            global = "";
        }
        return row(
                text(" " + Ui.truncate(hints, Math.max(0, width - global.length() - FOOTER_GAP))).fg(Color.CYAN),
                spacer(),
                text(global).dim()
        );
    }

    /**
     * Keys for the panel that actually has focus — bubbletea's "short help": the handful worth
     * knowing here, not everything that is bound here. A panel the backend cannot serve is never
     * focusable, so its keys never reach this line.
     */
    private String panelHints() {
        SidePanels sides = ui.sidePanels();
        if (sides.logFocused()) {
            return "↑/↓ scroll   PgUp/PgDn page   End follow";
        }
        return switch (sides.focused()) {
            case TOOLS -> "↑/↓ select   / filter   i install   u use   g global"
                    + (ctx.supports(BackendFeature.UPGRADE) ? "   p upgrade" : "   x uninstall");
            case TASKS -> "↑/↓ select   / filter   enter run   : args   . re-run";
            case ENV -> "↑/↓ select   / filter   y copy value";
            case ADVANCED -> "↑/↓ select   enter run   V hide";
            case STATUS -> "tab next panel   1-5 jump panel";
        };
    }

    /**
     * The always-available, non-advanced keys. The maintenance letters (T/E/D/U/X/B, P where
     * the backend has a plugin registry) all require {@code V} first, so they live in the
     * Advanced panel instead of cluttering this default hint line — {@code V} itself is the one
     * thing here that points at them.
     */
    private String globalKeyHints() {
        return "a add"
                + (ctx.supports(BackendFeature.PLUGIN_REGISTRY) ? "   p plugin" : "")
                + "   I apply config   e edit   V advanced   r refresh   ? help   q quit ";
    }
}
