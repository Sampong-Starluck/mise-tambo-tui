package com.sampong.tambo.tui.panel;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;

import com.sampong.tambo.tui.UiContext;

/** The {@code ?} key-reference overlay; remembers and restores focus around itself. */
public final class HelpOverlay {

    private final UiContext ctx;
    private boolean open;
    private String preOpenFocus;

    public HelpOverlay(UiContext ctx) {
        this.ctx = ctx;
    }

    public boolean isOpen() {
        return open;
    }

    public void open() {
        preOpenFocus = ctx.focusedId();
        ctx.clearFocus();
        open = true;
    }

    public void close() {
        open = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    public Element build() {
        return dialog("Help",
                text("tambo — a lazygit-style TUI for mise").bold(),
                text(""),
                helpLine("1-5", "Jump to a panel (5 = command log)"),
                helpLine("Tab / Shift+Tab", "Cycle panels"),
                helpLine("Up/Down, j/k", "Move selection / scroll"),
                helpLine("PgUp/PgDn", "Page up/down"),
                helpLine("Left/Right, h/l", "Pan the command log horizontally"),
                helpLine("End", "Log: resume following the newest entry"),
                helpLine("Mouse", "Click to focus a panel, wheel to scroll"),
                helpLine("a", "Add SDK — fuzzy-find registry modal"),
                helpLine("e", "Edit project mise.toml in-app (Ctrl+S save, Esc discard)"),
                helpLine("E", "Edit global mise config.toml in-app"),
                helpLine("A", "Activate mise in your shell profile (PowerShell, bash, zsh, fish)"),
                helpLine("T", "Trust this project's mise config (mise trust)"),
                helpLine("D", "Run mise doctor — full report in the log"),
                helpLine("U", "mise self-update"),
                helpLine("i", "Install selected tool"),
                helpLine("u", "Apply selected tool to project mise.toml"),
                helpLine("x", "Uninstall selected tool"),
                helpLine("g", "Install/set as global default"),
                helpLine("Enter", "Run selected task"),
                helpLine("r", "Refresh"),
                helpLine("q", "Quit"),
                helpLine("?", "Toggle this help"),
                text(""),
                text("In the Add SDK modal: type to fuzzy find, Enter to choose").dim(),
                text("the SDK, then again for the version. Ctrl+G = local/global.").dim(),
                text(""),
                text("Press ? or Esc to close").dim()
        ).rounded().borderColor(Color.CYAN).width(64);
    }

    private Element helpLine(String key, String description) {
        return row(text(key).bold().yellow().constraint(length(18)), text(description));
    }
}
