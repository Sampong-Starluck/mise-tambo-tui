package com.sampong.tambo.tui.pick;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo._common.model.OutdatedSdk;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.service.SdkVersionBackend;
import com.sampong.tambo.cli.TamboCommand;
import com.sampong.tambo.mise.implement.MiseSdkBackend;
import com.sampong.tambo.tui.pick.PickModel.Action;
import com.sampong.tambo.tui.pick.PickModel.Row;
import com.sampong.tambo.vfox.VfoxSdkBackend;

import dev.tamboui.toolkit.app.InlineApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.toolkit.event.GlobalEventHandler;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import lombok.NonNull;

/**
 * The inline version picker behind {@code mise-tambo pick <tool>}. It draws a few rows under the
 * shell prompt rather than taking over the screen, lets the user choose a version, and then gets
 * out of the way: the viewport is cleared on exit and the backend action runs with the inline UI
 * already gone, leaving one result line in the scrollback.
 * <p>
 * Versions are loaded before the viewport opens, synchronously. A picker that appears empty and
 * fills a moment later is worse than a short pause before it appears, and the backend listing is
 * the only slow part.
 * <p>
 * The action runs outside the UI on purpose: the backend's install/use output would otherwise
 * fight the viewport for the same rows. Its lines are discarded, since the one result line says
 * what happened.
 */
public final class PickApp extends InlineApp {

    /** Total viewport height: title, query, the list, and the key footer. */
    private static final int HEIGHT = 10;
    /** Rows left for the list once the title, query and footer take theirs. */
    private static final int LIST_ROWS = HEIGHT - 3;
    /** Width of the detail pane on the right of the list. */
    private static final int DETAIL_WIDTH = 38;
    /** Operation key handed to the backend, so a cancel can find the process it started. */
    private static final String CANCEL_KEY = "pick";

    private final PickModel model;
    private final SdkVersionBackend backend;
    private @Nullable Choice choice;

    /** What the user picked: the action and the row it applies to. */
    private record Choice(Action action, Row row) {
    }

    private PickApp(@NonNull PickModel model, @NonNull SdkVersionBackend backend) {
        this.model = model;
        this.backend = backend;
    }

    /**
     * Loads the tool's versions, shows the picker, performs whatever the user chose, and returns
     * the process exit code: 0 when the action succeeded, 1 on failure or cancel. Exactly one line
     * is printed to stdout either way.
     */
    public static int pick(@NonNull String tool, @NonNull SdkVersionBackend backend) throws Exception {
        List<SdkVersion> sdks = backend.listSdks();
        List<SdkRelease> releases = backend.listReleases(tool);
        @Nullable String latest = backend.listOutdated().stream()
                .filter(outdated -> tool.equals(outdated.name()))
                .map(OutdatedSdk::latest)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        PickModel model = new PickModel(backend.name(), tool, sdks, releases, latest);
        return new PickApp(model, backend).runPick();
    }

    /**
     * Picks the backend the way the full TUI does: an explicit {@code --backend} wins, then a
     * project config already in the working directory, and with neither (a first run) mise.
     * {@code AppLifecycle} owns the same rule for the TUI; it is repeated here because that one is
     * private to the lifecycle and the picker has no lifecycle to ask.
     */
    public static SdkVersionBackend backendFor(@NonNull TamboCommand command,
                                               @NonNull MiseSdkBackend mise, @NonNull VfoxSdkBackend vfox) {
        TamboCommand.Backend forced = command.backend();
        if (forced == TamboCommand.Backend.vfox) {
            return vfox;
        }
        if (forced == TamboCommand.Backend.mise) {
            return mise;
        }
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.exists(cwd.resolve(MiseSdkBackend.PROJECT_CONFIG_FILE))) {
            return mise;
        }
        if (Files.exists(cwd.resolve(VfoxSdkBackend.PROJECT_CONFIG_FILE))) {
            return vfox;
        }
        return mise;
    }

    /** Shows the picker until a choice or a cancel, then performs the choice. */
    private int runPick() throws Exception {
        run();
        Choice picked = choice;
        if (picked == null) {
            System.out.println("✗ " + model.tool() + ": cancelled");
            return 1;
        }
        return perform(picked);
    }

    /** Runs the chosen action with the inline UI already stopped, then prints the one result line. */
    private int perform(Choice picked) {
        Row row = picked.row();
        String spec = model.tool() + "@" + row.version();
        CliResult result = switch (picked.action()) {
            case USE_HERE -> backend.use(spec, false, line -> { }, CANCEL_KEY);
            case USE_GLOBAL -> backend.use(spec, true, line -> { }, CANCEL_KEY);
            case INSTALL_ONLY -> backend.install(spec, line -> { }, CANCEL_KEY);
        };
        if (!result.ok()) {
            String reason = result.summaryLine();
            System.out.println("✗ " + model.tool() + " " + row.version() + ": "
                    + (reason.isEmpty() ? "failed" : reason));
            return 1;
        }
        String target = switch (picked.action()) {
            case USE_HERE -> "→ " + backend.projectConfigFileName() + " (project)";
            case USE_GLOBAL -> "→ global";
            case INSTALL_ONLY -> "installed";
        };
        System.out.println("✓ " + model.tool() + " " + row.version() + " " + target);
        return 0;
    }

    // ==================== InlineApp hooks ====================

    @Override
    protected int height() {
        return HEIGHT;
    }

    /**
     * Clears the viewport on exit, so nothing is left behind but the result line. Without it the
     * picker's last frame would stay in the scrollback above that line.
     */
    @Override
    protected InlineTuiConfig configure(int height) {
        return InlineTuiConfig.builder(height).clearOnClose(true).build();
    }

    @Override
    protected void onStart() {
        runner().eventRouter().addGlobalHandler((GlobalEventHandler) this::onEvent);
    }

    /**
     * Keys are read by code rather than through the key-binding sets: the picker has three fixed
     * actions and no focusable widgets, so a binding layer would only be one more thing to get
     * wrong for Enter or the arrows.
     */
    private EventResult onEvent(Event event) {
        if (!(event instanceof KeyEvent key)) {
            return EventResult.UNHANDLED;
        }
        Action action = PickModel.actionFor(key);
        if (action != null) {
            // Enter with nothing matching does nothing: the picker stays open for another query.
            Row row = model.selected();
            if (row != null) {
                choice = new Choice(action, row);
                quit();
            }
            return EventResult.HANDLED;
        }
        if (key.code() == KeyCode.ESCAPE || key.isCtrlC()) {
            quit();
            return EventResult.HANDLED;
        }
        switch (key.code()) {
            case UP -> model.up();
            case DOWN -> model.down();
            case BACKSPACE -> model.backspace();
            case CHAR -> {
                if (!key.hasCtrl() && !key.hasAlt()) {
                    for (char c : Character.toChars(key.codePoint())) {
                        model.type(c);
                    }
                }
            }
            default -> {
                return EventResult.UNHANDLED;
            }
        }
        return EventResult.HANDLED;
    }

    @Override
    protected Element render() {
        Element title = text(model.tool() + " · pick a version").bold();
        Element query = row(
                text("› " + model.query() + "▏").bold(),
                spacer(),
                text(model.counter()).dim());
        Element body = row(listPane(), detailPane()).constraint(fill());
        Element footer = row(
                text("Enter").bold().cyan(), text(" use here  ").dim(),
                text("Ctrl+G").bold().cyan(), text(" use globally  ").dim(),
                text("Alt+I").bold().cyan(), text(" install only  ").dim(),
                text("Esc").bold().cyan(), text(" cancel").dim());
        return column(title, query, body, footer);
    }

    /** The visible window of the version list, with the cursor's row highlighted. */
    private Element listPane() {
        List<Row> rows = model.visible();
        if (rows.isEmpty()) {
            String message = model.query().isBlank()
                    ? "no versions of " + model.tool() + " found"
                    : "no version matches \"" + model.query() + "\"";
            return column(text(message).dim()).constraint(fill());
        }
        Row selected = model.selected();
        int first = model.firstVisible(LIST_ROWS);
        Element[] lines = new Element[Math.min(LIST_ROWS, rows.size() - first)];
        for (int i = 0; i < lines.length; i++) {
            Row candidate = rows.get(first + i);
            lines[i] = versionLine(candidate, candidate.equals(selected));
        }
        return column(lines).constraint(fill());
    }

    /** One line of the list: cursor, active/installed marker, version, and its note. */
    private static Element versionLine(Row row, boolean selected) {
        TextElement marker = text(row.active() ? "●" : row.installed() ? "○" : " ");
        if (row.active()) {
            marker = marker.green();
        }
        TextElement version = text(String.format("%-12s", row.version()));
        if (selected) {
            version = version.bold().cyan();
        }
        return row(
                text(selected ? "› " : "  ").bold(),
                marker,
                text(" "),
                version,
                text(row.note()).dim());
    }

    /**
     * The pane beside the list: what the selected version is, where it is pinned, whether
     * something newer exists, and the exact command Enter will run. That last line is what keeps
     * the picker honest, since the user sees the command before it runs.
     */
    private Element detailPane() {
        Row row = model.selected();
        if (row == null) {
            return column(pane("no version selected").dim()).constraint(length(DETAIL_WIDTH));
        }
        String status;
        if (row.installed()) {
            status = row.active() ? "installed · active here" : row.global() ? "installed · global" : "installed";
        } else {
            status = "not installed";
        }
        String pinned = model.pinnedPath(row);
        String latest = model.latest();
        Element[] lines = {
                pane(model.tool() + " " + row.version()).bold(),
                pane("status   " + status),
                pane("pinned   " + (pinned == null ? "not pinned here" : tilde(pinned))),
                pane(latest != null && !latest.equals(row.version()) ? "newer    " + latest + " ↑" : ""),
                pane(""),
                pane("will run " + willRun(row)).dim(),
        };
        return column(lines).constraint(length(DETAIL_WIDTH));
    }

    /** The command Enter runs for {@code row}, in the backend's own words. */
    private String willRun(Row row) {
        return model.backendName() + " use " + (row.global() ? "-g " : "")
                + model.tool() + "@" + row.version();
    }

    private static TextElement pane(String content) {
        return text("│ " + content);
    }

    /** Shortens a path under the home directory to {@code ~/...} for the narrow detail pane. */
    private static String tilde(String path) {
        String normalized = path.replace('\\', '/');
        String home = System.getProperty("user.home", "").replace('\\', '/');
        if (!home.isEmpty() && normalized.startsWith(home + "/")) {
            return "~" + normalized.substring(home.length());
        }
        return normalized;
    }
}
