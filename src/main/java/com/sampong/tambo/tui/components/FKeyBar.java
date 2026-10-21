package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;

import org.jspecify.annotations.Nullable;

/**
 * The Midnight Commander style strip of function-key labels along the bottom edge, for beginner
 * mode. Each key is shown as its number followed by a cyan label, so the bar doubles as a list of
 * what the F-keys do without any of them needing a hint of their own.
 * <p>
 * The strip is always ten equal cells, one per key. Every label is cut to the room its cell has,
 * so a narrow terminal loses the tail of a word rather than wrapping the bar onto a second line.
 */
public final class FKeyBar {

    /** One cell per function key, in order F1 to F10. */
    private static final String[] LABELS = {
            "Help", "Versions", "Install", "Use", "Global", "Upgrade", "Add SDK", "Remove", "Menu", "Quit"
    };
    /** The action each key runs, in the same order as {@link #LABELS}. */
    private static final String[] ACTIONS = {
            "help", "versions", "install", "use", "global", "upgrade", "add", "remove", "menu", "quit"
    };
    private FKeyBar() {
    }

    /** The bottom strip, spread evenly across {@code width} columns. */
    public static Element build(int width) {
        List<Element> cells = new ArrayList<>();
        int cellWidth = Math.max(1, width / LABELS.length);
        for (int i = 0; i < LABELS.length; i++) {
            String number = String.valueOf(i + 1);
            // Room left in the cell once the number is placed; never negative.
            int labelRoom = Math.max(0, cellWidth - number.length());
            String label = fit(LABELS[i], labelRoom);
            cells.add(row(
                    text(number).dim(),
                    text(label).fg(Color.BLACK).bg(Color.CYAN)
            ).fill());
        }
        return row(cells.toArray(new Element[0]));
    }

    /**
     * The action a function key runs, by name, or null when the key is not one of the ten the bar
     * shows. The caller maps the name to a command or panel; keeping that mapping out of here means
     * the bar and the key handling cannot be wired to different things by accident.
     */
    public static @Nullable String actionFor(KeyCode code) {
        int index = functionKeyIndex(code);
        return index < 0 ? null : ACTIONS[index];
    }

    /** Zero-based position of F1..F10 in the bar, or -1 for any other key. */
    private static int functionKeyIndex(KeyCode code) {
        return switch (code) {
            case F1 -> 0;
            case F2 -> 1;
            case F3 -> 2;
            case F4 -> 3;
            case F5 -> 4;
            case F6 -> 5;
            case F7 -> 6;
            case F8 -> 7;
            case F9 -> 8;
            case F10 -> 9;
            default -> -1;
        };
    }

    /** Shortens {@code label} to {@code room} columns, ending in an ellipsis when it had to be cut. */
    private static String fit(String label, int room) {
        if (room <= 0) {
            return "";
        }
        return Ui.truncate(label, room);
    }
}
