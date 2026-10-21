package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;

import com.sampong.tambo.tui.commands.Command;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * The beginner-mode menu bar: a row of named menus across the top and a dropdown under whichever
 * one is open. Each menu lists the {@link Command}s whose group it is named after, so the same
 * commands the Space menu and the palette offer are reachable here without knowing any key.
 * <p>
 * Three ways in, because a menu bar is for people who do not know the keys yet and so cannot be
 * gated behind one: a mouse click on a menu name, {@code F9}, and {@code Alt} plus the underlined
 * letter. F9 alone was not enough — VS Code's integrated terminal keeps F9 and F10 for its
 * debugger by default, so there the key never reaches the app at all.
 * <p>
 * Every command still shows its key on the right of the dropdown, which is the point: the menu
 * teaches the shortcut rather than replacing it. Like {@link ConfirmModal} it has no focusable
 * element of its own, so the global handler feeds it keys and clicks while it is open.
 */
public final class MenuBar implements Modal {

    /** The menus across the top, in bar order, each with the letter Alt opens it by. */
    public enum Menu {
        FILE("File", 'f'),
        TOOLS("Tools", 't'),
        PROJECT("Project", 'p'),
        TASKS("Tasks", 'a'),
        VIEW("View", 'v'),
        HELP("Help", 'h');

        private final String label;
        private final char mnemonic;

        Menu(String label, char mnemonic) {
            this.label = label;
            this.mnemonic = mnemonic;
        }

        public String label() {
            return label;
        }

        public char mnemonic() {
            return mnemonic;
        }
    }

    /** Padding either side of each menu name in the bar; {@link #dropdownColumn} depends on it. */
    private static final int CELL_PAD = 1;
    /** Narrowest the dropdown is allowed to be, so the footnote always fits. */
    private static final int MIN_INNER = 30;
    /** Rows of the dropdown above its first entry: the bar itself, then the dropdown's top border. */
    private static final int FIRST_ENTRY_ROW = 2;
    private static final String WARNING = " ⚠";
    private static final String HELP_TITLE = "Keyboard help";
    private static final String HELP_KEY = "F1";
    private static final String[] FOOTNOTE = {"", "The key on the right works", "anywhere, without the menu."};

    /** One dropdown row: the command it runs, or the synthetic keyboard-help entry. */
    private record Item(String title, String key, boolean asks, Runnable action) {

        String text() {
            return title + (asks ? WARNING : "");
        }
    }

    private final UiContext ctx;
    private boolean open;
    private Menu current = Menu.TOOLS;
    private List<Command> commands = List.of();
    private int selected;
    private @Nullable String preOpenFocus;
    /** What the synthetic "Keyboard help" entry does. The lead points it at the help overlay. */
    private Runnable openHelp = () -> {
    };

    public MenuBar(@NonNull UiContext ctx) {
        this.ctx = ctx;
    }

    /**
     * The menu a command is listed under. Clean-up and App commands live under File, the
     * usual home of "everything else" in a menu bar; Updates sits with Tools because it is about
     * the tools' own versions.
     */
    public static Menu menuFor(Command.Group group) {
        return switch (group) {
            case TOOLS, UPDATES -> Menu.TOOLS;
            case PROJECT -> Menu.PROJECT;
            case CLEANUP, APP -> Menu.FILE;
            case VIEW -> Menu.VIEW;
        };
    }

    /** The menu {@code Alt+key} opens, or null when {@code key} is not one of the underlined letters. */
    public static @Nullable Menu menuForMnemonic(int codePoint) {
        int lower = Character.toLowerCase(codePoint);
        for (Menu menu : Menu.values()) {
            if (menu.mnemonic() == lower) {
                return menu;
            }
        }
        return null;
    }

    /** Sets what Enter does on the synthetic "Keyboard help  F1" entry in the Help menu. */
    public void onKeyboardHelp(@NonNull Runnable openHelp) {
        this.openHelp = openHelp;
    }

    /** Opens the bar on the Tools menu, with focus taken off the panels behind it. */
    public void open(@NonNull List<Command> commands) {
        open(commands, Menu.TOOLS);
    }

    /** Opens the bar on {@code menu} — from a click on its name, or its Alt letter. */
    public void open(@NonNull List<Command> commands, @NonNull Menu menu) {
        this.commands = List.copyOf(commands);
        this.current = menu;
        this.selected = 0;
        if (!open) {
            this.preOpenFocus = ctx.focusedId();
        }
        ctx.clearFocus();
        this.open = true;
    }

    public void close() {
        open = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /** True while a dropdown is showing. */
    @Override
    public boolean isOpen() {
        return open;
    }

    /** The menu whose dropdown is showing, or would show first. */
    public Menu current() {
        return current;
    }

    /** The context-sensitive hint line the footer shows while the bar is open. */
    public String footerHint() {
        return "←→ menus  ↑↓ move  Enter run  Esc close";
    }

    // ==================== Rendering ====================

    /**
     * The one-line menu bar. Each name has its Alt letter underlined; the open menu is drawn
     * reversed so it reads as the selected one; the right edge says how to get in and out.
     */
    public Element barRow(boolean beginnerMode) {
        List<Span> spans = new ArrayList<>();
        for (Menu menu : Menu.values()) {
            boolean active = open && menu == current;
            Style base = active ? Style.create().reversed().bold() : Style.create();
            String label = menu.label();
            int at = label.toLowerCase(Locale.ROOT).indexOf(menu.mnemonic());
            spans.add(Span.styled(" ".repeat(CELL_PAD) + label.substring(0, at), base));
            spans.add(Span.styled(label.substring(at, at + 1), base.underlined()));
            spans.add(Span.styled(label.substring(at + 1) + " ".repeat(CELL_PAD), base));
        }
        return row(
                richText(Text.from(Line.from(spans))),
                spacer(),
                text(beginnerMode
                        ? "click, F9 or Alt+letter for menus · Space m to switch off "
                        : "F9 menus ").dim());
    }

    /**
     * The x offset, in columns from the left of the bar, where {@code menu}'s name — and so its
     * dropdown — starts. Derived from the same padding {@link #barRow} draws with, so the two
     * cannot drift.
     */
    public int dropdownColumn() {
        return columnOf(current);
    }

    private static int columnOf(Menu target) {
        int x = 0;
        for (Menu menu : Menu.values()) {
            if (menu == target) {
                return x;
            }
            x += menu.label().length() + 2 * CELL_PAD;
        }
        return 0;
    }

    /** The menu whose name covers column {@code x} of the bar, or null for the empty rest of it. */
    public static @Nullable Menu menuAt(int x) {
        for (Menu menu : Menu.values()) {
            int from = columnOf(menu);
            if (x >= from && x < from + menu.label().length() + 2 * CELL_PAD) {
                return menu;
            }
        }
        return null;
    }

    /**
     * The dropdown for the open menu, or null when the bar is closed. Every row is one span
     * padded to the full inner width: unlike a dialog, a panel stacked over the screen does not
     * clear what is under it, so any cell a row leaves unpainted would show the panels behind
     * bleeding through the menu.
     */
    public @Nullable Element dropdown() {
        if (!open) {
            return null;
        }
        List<Item> items = items(current);
        int inner = innerWidth(items);

        List<Element> rows = new ArrayList<>();
        if (items.isEmpty()) {
            rows.add(line(Line.styled(pad(" " + emptyMessage(current), inner), Style.create().dim())));
        }
        for (int i = 0; i < items.size(); i++) {
            rows.add(line(entryLine(items.get(i), i == selected, inner)));
        }
        for (String note : FOOTNOTE) {
            rows.add(line(Line.styled(pad(note.isEmpty() ? "" : " " + note, inner), Style.create().dim())));
        }
        return panel(current.label(), column(rows.toArray(new Element[0])))
                .rounded().borderColor(Color.CYAN).fit();
    }

    private static Element line(Line line) {
        return richText(Text.from(line));
    }

    /** Why a menu has no rows: Tasks is run from its panel, not from here. */
    private static String emptyMessage(Menu menu) {
        return menu == Menu.TASKS
                ? "Run tasks from the Tasks panel (4)"
                : "Nothing in this menu right now";
    }

    /** One entry: title on the left, its key on the right, the gap between them painted too. */
    private static Line entryLine(Item item, boolean selected, int inner) {
        String left = " " + item.text();
        String right = item.key() + " ";
        String gap = " ".repeat(Math.max(1, inner - left.length() - right.length()));
        if (selected) {
            return Line.styled(left + gap + right, Style.create().reversed().bold());
        }
        return Line.from(Span.raw(left + gap), Span.styled(right, Style.create().fg(Color.CYAN)));
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    /** Wide enough for the longest title, its ⚠ and its key, and never narrower than the footnote. */
    private static int innerWidth(List<Item> items) {
        int widest = MIN_INNER;
        for (Item item : items) {
            widest = Math.max(widest, item.text().length() + item.key().length() + 4);
        }
        return widest;
    }

    // ==================== Items ====================

    /** The rows a menu shows: its commands in the order they were given, plus help where it belongs. */
    private List<Item> items(Menu menu) {
        List<Item> items = new ArrayList<>();
        for (Command command : commands) {
            if (menuFor(command.group()) == menu) {
                items.add(new Item(command.title(), command.key(), command.asks(), command::run));
            }
        }
        if (menu == Menu.HELP && items.isEmpty()) {
            items.add(new Item(HELP_TITLE, HELP_KEY, false, () -> openHelp.run()));
        }
        return items;
    }

    // ==================== Keys and clicks ====================

    /**
     * Handles the keys while the bar is open. Returns true because the bar is modal: everything
     * is swallowed, including keys it does not use. Enter closes first and then runs the selected
     * item, so the item's own dialogs open over a bar that is already gone.
     */
    public boolean handleKey(@NonNull KeyEvent key) {
        if (!open) {
            return false;
        }
        Menu jump = key.hasAlt() && key.code() == KeyCode.CHAR ? menuForMnemonic(key.codePoint()) : null;
        if (jump != null) {
            current = jump;
            selected = 0;
        } else if (key.code() == KeyCode.LEFT) {
            switchMenu(-1);
        } else if (key.code() == KeyCode.RIGHT) {
            switchMenu(1);
        } else if (key.code() == KeyCode.UP) {
            moveSelection(-1);
        } else if (key.code() == KeyCode.DOWN) {
            moveSelection(1);
        } else if (key.code() == KeyCode.ENTER) {
            runEntry(selected);
        } else if (key.code() == KeyCode.ESCAPE || key.code() == KeyCode.F9) {
            close();
        }
        return true;
    }

    /**
     * A click anywhere while beginner mode is on. On a menu name it opens that menu (or closes
     * it, if it is the one already open); on an entry it runs it; anywhere else it closes the
     * dropdown. Returns true when the click was the bar's to handle — always, while it is open,
     * since a menu is modal and a click "through" it should not land on a panel.
     *
     * @param commands what to list if the click opens a menu
     */
    public boolean handleClick(@NonNull MouseEvent event, @NonNull List<Command> commands) {
        if (!event.isClick()) {
            return open;
        }
        if (event.y() == 0) {
            Menu menu = menuAt(event.x());
            if (menu == null) {
                boolean wasOpen = open;
                if (wasOpen) {
                    close();
                }
                return wasOpen;
            }
            if (open && menu == current) {
                close();
            } else {
                open(commands, menu);
            }
            return true;
        }
        if (!open) {
            return false;
        }
        int entry = event.y() - FIRST_ENTRY_ROW;
        int left = dropdownColumn();
        int right = left + innerWidth(items(current)) + 2;
        if (event.x() >= left && event.x() < right && entry >= 0 && entry < items(current).size()) {
            runEntry(entry);
        } else {
            close();
        }
        return true;
    }

    private void runEntry(int index) {
        List<Item> items = items(current);
        Item chosen = index >= 0 && index < items.size() ? items.get(index) : null;
        close();
        if (chosen != null) {
            chosen.action().run();
        }
    }

    private void switchMenu(int delta) {
        int count = Menu.values().length;
        current = Menu.values()[(current.ordinal() + delta + count) % count];
        selected = 0;
    }

    private void moveSelection(int delta) {
        int count = items(current).size();
        if (count == 0) {
            selected = 0;
            return;
        }
        selected = (selected + delta + count) % count;
    }
}
