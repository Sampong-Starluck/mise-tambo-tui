package com.sampong.tambo.tui.layout;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.stack;
import static dev.tamboui.toolkit.Toolkit.text;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.ContentAlignment;
import dev.tamboui.layout.Margin;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.Panel;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.util.AppVersion;
import com.sampong.tambo.tui.TuiComponents;
import com.sampong.tambo.tui.components.FKeyBar;
import com.sampong.tambo.tui.components.JobsView;
import com.sampong.tambo.tui.components.ProgressBar;
import com.sampong.tambo.tui.components.SidePanels;
import com.sampong.tambo.tui.components.Ui;
import com.sampong.tambo.tui.keys.GlobalKeyBindings;
import com.sampong.tambo.tui.state.ProgressSnapshot;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Everything about what's on screen. Turns {@link UiContext#state()} into {@link Element}s and
 * owns no key handling of its own — {@code com.sampong.tambo.tui.keys.GlobalKeyBindings} is the
 * counterpart that decides what a keypress does.
 * <p>
 * The shape keeps lazygit's numbered stack down the left, so an install running in Tools stays
 * in view while you read Tasks, and adds yazi's Miller columns to its right: with Tools focused,
 * a Versions column lists every version of the selected tool and the detail pane describes
 * whichever one is under the cursor — Tools → Versions → Detail, each column about the
 * selection in the one before it.
 * <p>
 * Below {@value #TWO_PANE_MIN_WIDTH} columns there is no room for columns side by side, so the
 * same path becomes k9s's drill-down: one column at a time under a breadcrumb that says where
 * you are. Below {@value #MIN_WIDTH}×{@value #MIN_HEIGHT} nothing usable fits at all, and the
 * screen says so — and how big it needs to be — instead of drawing a mangled layout.
 */
@RequiredArgsConstructor
public final class AppLayout {

    /** Smallest terminal anything is drawn in; below it, {@link #tooSmall} explains instead. */
    static final int MIN_WIDTH = 60;
    static final int MIN_HEIGHT = 16;
    /**
     * Below this terminal width the main pane is dropped and the stack takes the whole width.
     * Splitting a narrow terminal in two leaves a sidebar too cramped to show a version number
     * beside a name, and a pane too cramped to be worth the columns it cost.
     */
    static final int TWO_PANE_MIN_WIDTH = 96;
    /** Share of the width the sidebar asks for, before the min/max clamp. */
    private static final int SIDEBAR_PERCENT = 34;
    private static final int SIDEBAR_MIN_WIDTH = 42;
    private static final int SIDEBAR_MAX_WIDTH = 64;
    /** The Versions column: wide enough for a version, a marker and a tag. */
    private static final int VERSIONS_WIDTH = 30;
    /** The main area must keep at least this much for the detail pane before Versions joins it. */
    private static final int DETAIL_MIN_WIDTH = 40;
    /** Assumed terminal size when the backend cannot report one — a conventional 80x24 and a bit. */
    private static final int ASSUMED_WIDTH = 120;
    private static final int ASSUMED_HEIGHT = 40;
    /** Vertical split between the main pane and the command log beneath it. */
    private static final int MAIN_WEIGHT = 3;
    private static final int LOG_WEIGHT = 1;
    /** Columns kept clear between the footer's two halves so they never read as one sentence. */
    private static final int FOOTER_GAP = 3;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final String GLOBAL_HINTS = "Ctrl+P commands  Ctrl+O jump  F1 help  q quit";

    @NonNull
    private final UiContext ctx;
    @NonNull
    private final TuiComponents ui;
    @NonNull
    private final IntSupplier terminalHeight;
    @NonNull
    private final IntSupplier terminalWidth;

    public Element render() {
        if (ui.selectBackendModal().isOpen()) {
            // Nothing else is decided yet (the header alone would already ask the backend for
            // its version, firing a command before the user has even chosen mise vs vfox) —
            // show only the picker over a blank backdrop.
            return stack(text(""), ui.selectBackendModal().build());
        }
        int width = size(terminalWidth, ASSUMED_WIDTH);
        int height = size(terminalHeight, ASSUMED_HEIGHT);
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            return tooSmall(width, height);
        }

        boolean beginner = ctx.state().beginnerMode();
        List<Element> rows = new ArrayList<>();
        if (beginner) {
            rows.add(sized(ui.menuBar().barRow(true), length(1)));
        }
        rows.add(sized(buildHeader(width), length(1)));
        rows.add(sized(buildBody(width, height), fill()));
        String toast = ctx.state().toast();
        if (toast != null) {
            rows.add(sized(toastRow(toast), length(1)));
        }
        if (ui.commandLine().isOpen()) {
            Element popup = ui.commandLine().suggestionsPopup();
            if (popup != null) {
                rows.add(popup);
            }
            rows.add(sized(ui.commandLine().build(), length(ui.commandLine().height())));
        } else {
            rows.add(sized(buildFooter(width), length(1)));
        }
        if (beginner) {
            rows.add(sized(FKeyBar.build(width), length(1)));
        }
        Element screen = column(rows.toArray(new Element[0]));

        Element overlay = overlay();
        if (overlay != null) {
            return stack(screen, overlay);
        }
        Element dropdown = ui.menuBar().dropdown();
        if (dropdown != null) {
            // Hangs from its label in the bar, like any menu — not centred like a dialog.
            return stack(screen, stack(dropdown)
                    .alignment(ContentAlignment.TOP_LEFT)
                    .margin(new Margin(1, 0, 0, ui.menuBar().dropdownColumn())));
        }
        return screen;
    }

    /** Whichever dialog is up, topmost first, or null for none. */
    private @Nullable Element overlay() {
        if (ui.helpOverlay().isOpen()) {
            return ui.helpOverlay().build();
        }
        if (ui.confirmModal().isOpen()) {
            return ui.confirmModal().build();
        }
        if (ui.batchConfirm().isOpen()) {
            return ui.batchConfirm().build();
        }
        if (ui.palette().isOpen()) {
            return ui.palette().build();
        }
        if (ui.leaderMenu().isOpen()) {
            return ui.leaderMenu().build();
        }
        if (ui.registryModal().isOpen()) {
            return ui.registryModal().build();
        }
        if (ui.configEditor().isOpen()) {
            return ui.configEditor().build();
        }
        if (ui.switchBackendModal().isOpen()) {
            return ui.switchBackendModal().build();
        }
        if (ui.taskArgsModal().isOpen()) {
            return ui.taskArgsModal().build();
        }
        if (ui.addPluginModal().isOpen()) {
            return ui.addPluginModal().build();
        }
        if (ui.autoInstallModal().isOpen()) {
            return ui.autoInstallModal().build();
        }
        return null;
    }

    /**
     * The guard for a terminal nothing fits in. It says what the size is and what it needs to be,
     * and that resizing is all it takes — the next frame after a resize redraws normally.
     */
    private Element tooSmall(int width, int height) {
        return column(
                spacer(),
                row(spacer(), text("tambo needs a bigger window").bold(), spacer()),
                text(""),
                row(spacer(), text("now     ").dim(), text(width + " × " + height).fg(Color.RED).bold(), spacer()),
                row(spacer(), text("needs   ").dim(), text(MIN_WIDTH + " × " + MIN_HEIGHT).fg(Color.GREEN).bold(), spacer()),
                text(""),
                row(spacer(), text("Resize the terminal and the UI comes back by itself.").dim(), spacer()),
                text(""),
                row(spacer(), text("q").cyan().bold(), text(" quit").dim(), spacer()),
                spacer());
    }

    // ==================== Body ====================

    private Element buildBody(int width, int height) {
        if (ui.jobsView().isOpen()) {
            return ui.jobsView().build();
        }
        if (ctx.state().toolsFullScreen()) {
            return labelled(ui.toolsPanel().build(), SidePanels.Side.TOOLS);
        }
        int bodyHeight = Math.max(6, height - 2);
        if (width < TWO_PANE_MIN_WIDTH) {
            return narrowBody(bodyHeight);
        }

        int sidebarWidth = Math.clamp(width * SIDEBAR_PERCENT / 100L, SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH);
        int mainWidth = width - sidebarWidth;
        SdkVersion tool = ui.toolsPanel().selected();
        boolean showVersions = ui.sidePanels().focused() == SidePanels.Side.TOOLS && tool != null
                && mainWidth - VERSIONS_WIDTH >= DETAIL_MIN_WIDTH;

        Element main;
        if (showVersions) {
            main = row(
                    jumpLabel(ui.versionsPanel().build(tool.name()), GlobalKeyBindings.JUMP_VERSIONS, "Versions")
                            .constraint(length(VERSIONS_WIDTH)),
                    ui.detailPanel().build(mainWidth - VERSIONS_WIDTH).constraint(fill()));
        } else {
            main = ui.detailPanel().build(mainWidth);
        }
        return row(
                buildSidebar(bodyHeight).constraint(length(sidebarWidth)),
                column(
                        sized(main, fill(MAIN_WEIGHT)),
                        labelledLog(mainWidth).constraint(fill(LOG_WEIGHT))
                ).constraint(fill()));
    }

    /**
     * 80×24 and the like: one column at a time. The stack and the log as usual, until Enter
     * drills into a tool — then the Versions column takes the width under a breadcrumb, and Enter
     * again swaps it for the detail. Esc walks back up the same path.
     */
    private Element narrowBody(int bodyHeight) {
        SdkVersion tool = ui.toolsPanel().selected();
        boolean drilled = tool != null && (ui.sidePanels().versionsFocused() || ctx.state().narrowDetail());
        if (!drilled) {
            int stackHeight = bodyHeight * MAIN_WEIGHT / (MAIN_WEIGHT + LOG_WEIGHT);
            return column(
                    buildSidebar(stackHeight).constraint(fill(MAIN_WEIGHT)),
                    labelledLog(size(terminalWidth, ASSUMED_WIDTH)).constraint(fill(LOG_WEIGHT)));
        }
        boolean detail = ctx.state().narrowDetail();
        Line crumb = Line.from(
                Span.styled(" tambo", Style.create().fg(Color.CYAN).bold()),
                Span.styled(" › ", Style.create().fg(Color.DARK_GRAY)),
                Span.raw("Tools"),
                Span.styled(" › ", Style.create().fg(Color.DARK_GRAY)),
                Span.styled(tool.name(), Style.create().bold()),
                detail ? Span.styled(" › detail", Style.create().fg(Color.DARK_GRAY)) : Span.raw(""));
        Element content = detail
                ? ui.detailPanel().build(size(terminalWidth, ASSUMED_WIDTH))
                : ui.versionsPanel().build(tool.name());
        return column(
                richText(Text.from(crumb)).constraint(length(1)),
                sized(content, fill()),
                text(detail ? " Esc back to versions" : " detail hidden at this width · Enter opens it")
                        .dim().constraint(length(1)));
    }

    /** The numbered panel stack, each panel sized by {@link SidePanels#constraint}. */
    private StyledElement<?> buildSidebar(int sidebarHeight) {
        SidePanels sides = ui.sidePanels();
        List<Element> stacked = new ArrayList<>();
        for (SidePanels.Side side : sides.visible()) {
            stacked.add(buildSide(side).constraint(sides.constraint(side, sidebarHeight)));
        }
        return column(stacked.toArray(new Element[0]));
    }

    /**
     * One panel of the stack. A panel the backend cannot serve still renders — collapsed to its
     * title bar, dimmed, and not focusable — so the stack keeps the same slots and the same
     * number keys whichever backend is driving.
     */
    private StyledElement<?> buildSide(SidePanels.Side side) {
        SidePanels sides = ui.sidePanels();
        if (!sides.available(side)) {
            return panel(SidePanels.unavailableTitle(side))
                    .rounded()
                    .borderColor(ctx.theme().idle());
        }
        Panel built = switch (side) {
            case STATUS -> ui.statusPanel().build();
            case TOOLS -> ui.toolsPanel().build();
            case ENV -> ui.envPanel().build();
            case TASKS -> ui.tasksPanel().build();
        };
        return labelled(built, side);
    }

    private StyledElement<?> labelledLog(int width) {
        Element log = ui.logPanel().build(width);
        return log instanceof Panel panel ? jumpLabel(panel, '5', "Log") : (StyledElement<?>) log;
    }

    private Panel labelled(Panel panel, SidePanels.Side side) {
        return jumpLabel(panel, side.key(), side.title());
    }

    /**
     * In jump mode (Ctrl+O) a panel's title becomes its label on a bright chip — Posting's jump
     * overlay — so the key that reaches each panel is printed on the panel itself.
     */
    private Panel jumpLabel(Panel panel, char key, String title) {
        if (!ctx.state().jumpMode()) {
            return panel;
        }
        return panel.title(Line.from(
                Span.raw(" "),
                Span.styled(" " + key + " ", Style.create().fg(Color.BLACK).bg(Color.YELLOW).bold()),
                Span.raw(" " + title + " ")));
    }

    /** Gives {@code element} a layout constraint, wrapping it when it is not styleable itself. */
    private static Element sized(Element element, Constraint constraint) {
        StyledElement<?> styled = element instanceof StyledElement<?> s ? s : column(element);
        return styled.constraint(constraint);
    }

    /** Falls back to an assumed size when the terminal cannot report one (it returns MAX_VALUE). */
    private static int size(IntSupplier source, int assumed) {
        int reported = source.getAsInt();
        return reported <= 0 || reported == Integer.MAX_VALUE ? assumed : reported;
    }

    // ==================== Header ====================

    /**
     * Where you are and what state it is in, at a glance: the folder, the backend and its config
     * file, whether that file is trusted — then on the right, what is running, how much is out of
     * date, and whether the network is in play. Offline replaces that last part with how old the
     * data on screen is, since that is the thing worth knowing about it.
     */
    private Element buildHeader(int width) {
        String name = ctx.backend().name();
        ctx.actions().ensureBackendInfo();
        List<Element> left = new ArrayList<>();
        left.add(text(" tambo ").bold().cyan());
        left.add(appVersion());
        // The folder is the first thing to go on a narrow terminal: the shell prompt already
        // says it, while nothing else on screen says which backend or whether it is trusted.
        left.add(text(width >= 110 ? "  " + Ui.truncate(workingDir(), width / 3) + "  ·  " : "  ").dim());
        left.add(text(name));
        left.add(text(" · " + ctx.backend().projectConfigFileName()).dim());
        if (ctx.supports(BackendFeature.TRUST) && ctx.state().trustLazy().everLoaded()) {
            left.add(ctx.state().allTrusted() ? text(" · trusted").dim() : text(" · not trusted").fg(Color.RED));
        }
        if (ctx.supports(BackendFeature.DOCTOR) && ctx.state().backendInfoLazy().everLoaded()
                && !ctx.state().backendInfo().activated()) {
            left.add(text(" · not activated").yellow());
        }

        List<Element> right = new ArrayList<>();
        Element activity = activity();
        if (activity != null) {
            right.add(activity);
        }
        int outdated = ctx.state().outdated().size();
        if (outdated > 0) {
            right.add(text("↑ " + outdated + " outdated  ").yellow());
        }
        if (ctx.state().offline()) {
            var loadedAt = ctx.state().loadedAt();
            right.add(text("⚠ offline" + (loadedAt == null ? "" : " · data from " + loadedAt.format(CLOCK))
                    + (width >= 110 ? " · installs paused " : " ")).yellow());
        } else if (width >= 110) {
            right.add(text("online ").dim());
        }

        List<Element> all = new ArrayList<>(left);
        all.add(spacer());
        all.addAll(right);
        return row(all.toArray(new Element[0]));
    }

    /**
     * The activity indicator: the jobs summary when installs are running or queued, else — for
     * the one download the registry modal starts before its tool has a row — that bar, else a
     * plain count of whatever else is busy (a task, doctor).
     */
    private @Nullable Element activity() {
        String jobs = JobsView.headerSummary(ctx.actions().jobs());
        if (!jobs.isEmpty()) {
            return text(jobs + "  ").fg(ctx.actions().jobs().failed() > 0 && ctx.actions().jobs().running() == 0
                    ? Color.RED : Color.CYAN);
        }
        if (!ctx.state().anyBusy()) {
            return null;
        }
        List<ProgressSnapshot> progress = ctx.state().allProgress();
        if (ctx.state().busyCount() == 1 && progress.size() == 1) {
            return text(Ui.spinner() + " " + ProgressBar.compact(progress.getFirst()) + "  ").cyan();
        }
        return text(Ui.spinner() + " " + ctx.state().busyCount() + " running  ").cyan();
    }

    /**
     * tambo's own version beside its name — the same string {@code --version} prints, so a
     * screenshot of the header says which build it came from.
     */
    private static Element appVersion() {
        String version = AppVersion.get();
        return text(AppVersion.DEVELOPMENT.equals(version) ? "dev" : "v" + version).cyan();
    }

    private static String workingDir() {
        String dir = System.getProperty("user.dir", "");
        String home = System.getProperty("user.home", "");
        if (!home.isEmpty() && dir.startsWith(home)) {
            dir = "~" + dir.substring(home.length());
        }
        return dir.replace('\\', '/');
    }

    // ==================== Footer ====================

    private Element toastRow(String toast) {
        Element message = text(" ✓ " + toast).fg(Color.GREEN);
        return ctx.state().toastUndoable()
                ? row(message, text("  ·  ").dim(), text("z").cyan().bold(), text(" undo").dim(), spacer())
                : row(message, spacer());
    }

    /**
     * One line: the keys for whatever has focus on the left, styled key-then-word, and the four
     * that work everywhere on the right. On a narrow terminal the right half goes first — every
     * one of those keys is also in the Ctrl+P palette, which is what makes the line short.
     */
    private Element buildFooter(int width) {
        if (ctx.state().jumpMode()) {
            return row(text(" JUMP ").fg(Color.BLACK).bg(Color.YELLOW).bold(),
                    text("  type a label to move there   ").dim(), text("Esc").cyan().bold(), text(" cancel").dim(),
                    spacer(), text("numbers always mean the same panels ").dim());
        }
        String mode = null;
        String hints = modalHints();
        if (hints == null) {
            int marked = ctx.state().marked().size();
            if (marked > 0) {
                mode = marked + " MARKED";
                hints = "U upgrade marked  x uninstall marked  m toggle  Ctrl+A mark all outdated  Esc clear";
            } else {
                hints = panelHints();
            }
        }
        String global = GLOBAL_HINTS;
        int modeWidth = mode == null ? 0 : mode.length() + 3;
        if (modeWidth + hints.length() + global.length() + FOOTER_GAP > width) {
            global = "";
        }
        List<Element> parts = new ArrayList<>();
        parts.add(text(" "));
        if (mode != null) {
            parts.add(text(" " + mode + " ").fg(Color.BLACK).bg(Color.YELLOW).bold());
            parts.add(text("  "));
        }
        parts.addAll(styledHints(Ui.truncate(hints, Math.max(0, width - modeWidth - global.length() - FOOTER_GAP))));
        parts.add(spacer());
        if (!global.isEmpty()) {
            parts.addAll(styledHints(global));
            parts.add(text(" "));
        }
        return row(parts.toArray(new Element[0]));
    }

    /**
     * A hint string — pairs of {@code key word…} separated by two spaces — as styled spans: the
     * key bold cyan, its words dim. Keeps every hint line in the app one plain string to write.
     */
    static List<Element> styledHints(String hints) {
        List<Element> parts = new ArrayList<>();
        String[] groups = hints.strip().split(" {2,}");
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            int space = group.indexOf(' ');
            if (space < 0) {
                parts.add(text(group).cyan().bold());
            } else {
                parts.add(text(group.substring(0, space)).cyan().bold());
                parts.add(text(group.substring(space)).dim());
            }
            if (i < groups.length - 1) {
                parts.add(text("  "));
            }
        }
        return parts;
    }

    /** The hint line of whichever modal or view owns the keyboard, or null when none does. */
    private @Nullable String modalHints() {
        if (ui.confirmModal().isOpen()) {
            return ui.confirmModal().footerHint();
        }
        if (ui.batchConfirm().isOpen()) {
            return ui.batchConfirm().footerHint();
        }
        if (ui.palette().isOpen()) {
            return ui.palette().footerHint();
        }
        if (ui.leaderMenu().isOpen()) {
            return ui.leaderMenu().footerHint();
        }
        if (ui.menuBar().isOpen()) {
            return ui.menuBar().footerHint();
        }
        if (ui.switchBackendModal().isOpen()) {
            return ui.switchBackendModal().footerHint();
        }
        if (ui.taskArgsModal().isOpen()) {
            return ui.taskArgsModal().footerHint();
        }
        if (ui.addPluginModal().isOpen()) {
            return ui.addPluginModal().footerHint();
        }
        if (ui.autoInstallModal().isOpen()) {
            return ui.autoInstallModal().footerHint();
        }
        if (ui.registryModal().isOpen()) {
            return ui.registryModal().footerHint();
        }
        if (ui.configEditor().isOpen()) {
            return ui.configEditor().footerHint();
        }
        if (ui.jobsView().isOpen()) {
            return ui.jobsView().footerHint();
        }
        return null;
    }

    /**
     * Keys for the panel that actually has focus — bubbletea's "short help": the handful worth
     * knowing here, not everything that is bound here. Everything else is in F1 and Ctrl+P.
     */
    private String panelHints() {
        SidePanels sides = ui.sidePanels();
        if (sides.logFocused()) {
            return "↑↓ scroll  PgUp/PgDn page  End follow";
        }
        if (sides.versionsFocused()) {
            return ui.versionsPanel().footerHint();
        }
        if (ctx.state().toolsFullScreen()) {
            return "m mark  U upgrade  x uninstall  o outdated only  + / Esc back";
        }
        return switch (sides.focused()) {
            case TOOLS -> "Enter versions  i install  u use  g global"
                    + (ctx.supports(BackendFeature.UPGRADE) ? "  U upgrade" : "")
                    + "  m mark  / filter";
            case TASKS -> "Enter run  > args  . re-run  c cancel  / filter";
            case ENV -> "↑↓ select  / filter  y copy value";
            case STATUS -> (ctx.supports(BackendFeature.TRUST) && !ctx.state().allTrusted() ? "Space t trust  " : "")
                    + "e edit config  Tab next panel  Space more";
        };
    }
}
