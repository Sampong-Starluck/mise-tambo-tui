package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textInput;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;

import com.sampong.tambo.tui.commands.Command;
import com.sampong.tambo.tui.commands.CommandMatcher;
import com.sampong.tambo.tui.commands.CommandMatcher.Match;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The Ctrl+P command palette: a fuzzy-searchable list of every {@link Command}, run with Enter.
 * <p>
 * Typing a leading {@code :} is the one exception: the palette closes and hands whatever follows
 * to {@code openCommandLine}, so the palette is also the front door to the free-form
 * {@code :} mise command line without a second shortcut to learn.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}. The input box is
 * the focused element while open, so typed characters reach it through the normal focus path;
 * {@link #handleKey} only deals with the keys that move the selection or end the dialog.
 */
@RequiredArgsConstructor
public final class CommandPalette implements Modal {

    private static final int WIDTH = 70;
    private static final int VISIBLE_ROWS = 10;
    private static final char COMMAND_LINE_PREFIX = ':';

    @NonNull
    private final UiContext ctx;

    @NonNull
    private final Consumer<String> openCommandLine;

    @Getter
    private boolean open;
    private List<Command> all = List.of();
    private final TextInputState search = new TextInputState();
    private String lastQuery = "";
    private int index;
    private @Nullable String preOpenFocus;

    /** Opens the palette over {@code commands}, with an empty query and the first row selected. */
    public void open(@NonNull List<Command> commands) {
        all = List.copyOf(commands);
        search.clear();
        lastQuery = "";
        index = 0;
        preOpenFocus = ctx.focusedId();
        open = true;
        ctx.focus(PanelIds.PALETTE_INPUT);
    }

    /**
     * Closes the palette and gives focus back to whatever had it before. Callers that run a
     * command call this first, so the command's own dialog (if it opens one) takes focus cleanly
     * instead of being overwritten by the restore.
     */
    public void close() {
        open = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        } else {
            ctx.clearFocus();
        }
    }

    /** The context-sensitive hint line the footer shows while the palette is open. */
    public String footerHint() {
        return "↑↓ move  Enter run  Esc close";
    }

    /** What the user has typed so far. */
    public String query() {
        return search.text();
    }

    /** The command Enter would run right now, or null when nothing matches. */
    public @Nullable Command selected() {
        List<Match> shown = matches();
        return shown.isEmpty() ? null : shown.get(index).command();
    }

    /**
     * Handles the keys the palette owns. Returns true because the palette is modal and swallows
     * everything it does not act on; text editing is left to the input box itself.
     */
    public boolean handleKey(KeyEvent event) {
        if (event.isCancel()) {
            close();
            return true;
        }
        if (event.isConfirm()) {
            runSelected();
            return true;
        }
        if (event.isUp()) {
            move(-1);
            return true;
        }
        if (event.isDown()) {
            move(1);
            return true;
        }
        // The input may apply the typed ':' before or after this handler sees it, so both the
        // empty-query case and the already-typed ':' case route to the command line.
        String q = search.text();
        if (event.isChar(COMMAND_LINE_PREFIX) && q.isEmpty()) {
            routeToCommandLine("");
        } else if (q.startsWith(String.valueOf(COMMAND_LINE_PREFIX))) {
            routeToCommandLine(q.substring(1));
        }
        return true;
    }

    /**
     * Types {@code text} into the query as if the user had typed it. Lets tests drive the palette
     * without a terminal; a leading {@code :} routes to the command line, as it does for keys.
     */
    void type(String text) {
        search.insert(text);
        String q = search.text();
        if (q.startsWith(String.valueOf(COMMAND_LINE_PREFIX))) {
            routeToCommandLine(q.substring(1));
        }
    }

    // ==================== Rendering ====================

    public Element build() {
        List<Match> shown = matches();

        List<Element> content = new ArrayList<>();
        content.add(row(
                text("› ").fg(Color.CYAN).bold(),
                textInput(search)
                        .placeholder("type to filter commands")
                        .placeholderColor(Color.DARK_GRAY)
                        .id(PanelIds.PALETTE_INPUT)
                        .focusable(true)
                        .onKeyEvent(event -> {
                            handleKey(event);
                            return EventResult.HANDLED;
                        })
                        .fill(),
                text(shown.size() + " of " + all.size()).dim()
        ));

        if (shown.isEmpty()) {
            content.add(text("no command matches \"" + query() + "\"").dim());
        } else {
            // Show a window of rows that follows the selection, as RegistryModal does.
            int start = Math.max(0, index - VISIBLE_ROWS + 1);
            int end = Math.min(shown.size(), start + VISIBLE_ROWS);
            for (int i = start; i < end; i++) {
                content.add(resultRow(shown.get(i), i == index));
            }
        }

        content.add(text(""));
        content.add(text("↑↓ move   Enter run   Esc close   start with : to type a mise command").dim());

        return dialog("Commands", content.toArray(new Element[0]))
                .rounded().borderColor(Color.CYAN).width(WIDTH);
    }

    private Row resultRow(Match match, boolean selected) {
        Command c = match.command();
        List<Element> cells = new ArrayList<>();
        cells.add(selected
                ? text("› ").fg(Color.CYAN).bold().reversed()
                : text("  "));
        cells.addAll(titleCells(c.title(), match.highlight(), selected));
        cells.add(spacer());
        cells.add(selected
                ? text(c.group().label() + " ").dim().reversed()
                : text(c.group().label() + " ").dim());
        if (!c.key().isEmpty()) {
            cells.add(selected
                    ? text("[" + c.key() + "]").cyan().reversed()
                    : text("[" + c.key() + "]").cyan());
        }
        return row(cells.toArray(new Element[0]));
    }

    /**
     * The title split into runs: matched characters yellow and bold, everything else plain. Runs
     * keep the title's text intact, so the row reads the same with or without the colouring.
     */
    private static List<Element> titleCells(String title, List<Integer> highlight, boolean selected) {
        List<Element> cells = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        boolean runHit = false;
        for (int i = 0; i < title.length(); i++) {
            boolean hit = highlight.contains(i);
            if (i > 0 && hit != runHit) {
                cells.add(titleRun(run.toString(), runHit, selected));
                run.setLength(0);
            }
            run.append(title.charAt(i));
            runHit = hit;
        }
        if (!run.isEmpty()) {
            cells.add(titleRun(run.toString(), runHit, selected));
        }
        return cells;
    }

    private static Element titleRun(String s, boolean hit, boolean selected) {
        var t = hit ? text(s).fg(Color.YELLOW).bold() : text(s);
        return selected ? t.reversed() : t;
    }

    // ==================== Key handling ====================

    private void move(int delta) {
        index = Ui.clamp(index + delta, matches().size());
    }

    private void runSelected() {
        Command command = selected();
        if (command == null) {
            return; // nothing to run; stay open so the user can keep typing
        }
        close();
        command.run();
    }

    private void routeToCommandLine(String rest) {
        close();
        openCommandLine.accept(rest);
    }

    /**
     * The commands matching the current query, best first. Also resets the selection to the top
     * whenever the query has changed since the last call, so typing always starts from the best
     * match rather than wherever the arrow keys left off.
     */
    private List<Match> matches() {
        String q = search.text();
        if (!q.equals(lastQuery)) {
            lastQuery = q;
            index = 0;
        }
        List<Match> shown = CommandMatcher.rank(q, all);
        index = Ui.clamp(index, shown.size());
        return shown;
    }
}
