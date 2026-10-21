package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import com.sampong.tambo.tui.commands.Command;
import com.sampong.tambo.tui.state.UiContext;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The Space "which-key" menu (Helix-style): press Space, see every command that has a second key,
 * press that key to run it. Only commands with a {@link Command#leaderKey() leader key} appear.
 * <p>
 * Unlike the palette there is no text input, so it has no focusable element and consumes keys
 * through the global handler, the same way {@link ConfirmModal} does. A key that is not a leader
 * key is swallowed and the menu stays open, so a stray keystroke never falls through to a panel
 * shortcut underneath.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}.
 */
@RequiredArgsConstructor
public final class LeaderMenu implements Modal {

    private static final int WIDTH = 80;
    /** Title room in each of the two columns, after the key and the ⚠ marker. */
    private static final int TITLE_WIDTH = 31;

    @NonNull
    private final UiContext ctx;

    @Getter
    private boolean open;
    private List<Command> commands = List.of();
    private @Nullable String preOpenFocus;

    /**
     * Opens the menu over {@code all}, keeping only the commands that have a leader key — the
     * others are reachable from the palette and the menu bar, not from here.
     */
    public void open(@NonNull List<Command> all) {
        commands = all.stream().filter(c -> c.leaderKey() != null).toList();
        preOpenFocus = ctx.focusedId();
        open = true;
        // No input to focus — clear focus so no panel handles keys underneath.
        ctx.clearFocus();
    }

    public void close() {
        open = false;
        commands = List.of();
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /** The context-sensitive hint line the footer shows while the menu is open. */
    public String footerHint() {
        return "SPACE  pick a second key   Esc cancel";
    }

    /**
     * Handles the second key. Esc and Backspace step out; a key equal to a command's leader key
     * runs it. The match is case-sensitive, so {@code C} and {@code c} are different commands.
     * Returns true because the menu consumes every key while open.
     */
    public boolean handleKey(KeyEvent key) {
        if (key.isCancel() || key.code() == KeyCode.BACKSPACE) {
            close();
            return true;
        }
        for (Command command : commands) {
            if (key.isChar(command.leaderKey())) {
                // Close before running: the command may open its own dialog, and that must not
                // be undone by this menu restoring focus afterwards.
                close();
                command.run();
                return true;
            }
        }
        return true; // modal: swallow anything that is not a leader key
    }

    public Element build() {
        List<Command.Group> groups = Arrays.stream(Command.Group.values())
                .filter(g -> commands.stream().anyMatch(c -> c.group() == g))
                .toList();

        List<Element> content = new ArrayList<>();
        if (groups.isEmpty()) {
            content.add(text("no commands are bound to a second key").dim());
        }
        // Two groups side by side per row; an odd last group gets an empty column beside it so
        // it keeps the same width.
        for (int i = 0; i < groups.size(); i += 2) {
            Element left = groupColumn(groups.get(i));
            Element right = i + 1 < groups.size() ? groupColumn(groups.get(i + 1)) : column().fill();
            content.add(row(left, right));
            if (i + 2 < groups.size()) {
                content.add(text(""));
            }
        }

        content.add(text(""));
        content.add(text("⚠ asks before running · Backspace back · Esc close").dim());

        return dialog("Space …", content.toArray(new Element[0]))
                .rounded().borderColor(Color.CYAN).width(WIDTH);
    }

    private Element groupColumn(Command.Group group) {
        List<Element> lines = new ArrayList<>();
        lines.add(text(group.label().toUpperCase(Locale.ROOT)).dim());
        for (Command c : commands) {
            if (c.group() != group) {
                continue;
            }
            lines.add(row(
                    text(String.valueOf(c.leaderKey())).bold().cyan(),
                    text("  " + Ui.truncate(c.title(), TITLE_WIDTH)),
                    c.asks() ? text(" ⚠").yellow() : text("")
            ));
        }
        return column(lines.toArray(new Element[0])).fill();
    }
}
