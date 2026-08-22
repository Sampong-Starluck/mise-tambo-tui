package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;

import com.sampong.tambo.tui.state.UiContext;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** The {@code ?} key-reference overlay; remembers and restores focus around itself. */
@RequiredArgsConstructor
public final class HelpOverlay {

    @NonNull
    private final UiContext ctx;
    @Getter
    private boolean open;
    private @Nullable String preOpenFocus;

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
        boolean vfox = ctx.state().vfox();
        String tool = vfox ? "vfox" : "mise";
        String configFile = vfox ? ".vfox.toml" : "mise.toml";

        List<Element> lines = new ArrayList<>(List.of(
                text("tambo — a lazygit-style TUI for " + tool).bold(),
                text(""),
                helpLine(vfox ? "2, 5" : "1-5", "Jump to a panel (5 = command log)"),
                helpLine("6", "[advanced] Jump to the Advanced panel (only once V is on)"),
                helpLine("Tab / Shift+Tab", "Cycle panels"),
                helpLine("Up/Down, j/k", "Move selection / scroll"),
                helpLine("/", vfox ? "Filter the focused list; Esc clears"
                        : "Filter the focused list (Tools/Env/Tasks); Esc clears"),
                helpLine("PgUp/PgDn", "Page up/down"),
                helpLine("Left/Right, h/l", "Pan the focused panel / command log horizontally"),
                helpLine("End", "Log: resume following the newest entry"),
                helpLine("Mouse", "Click to focus a panel, wheel to scroll"),
                helpLine("a", vfox ? "Add SDK — install another version of a plugin you already have"
                        : "Add SDK — fuzzy-find registry modal"),
                helpLine("e", "Edit project " + configFile + " in-app (Ctrl+S save, Esc discard)")));
        if (vfox) {
            lines.add(helpLine("p", "Add plugin — fuzzy-find the vfox catalog and register it"));
        }

        lines.add(helpLine("V", "Toggle the Advanced panel — an actionable menu for [advanced] keys below"));
        if (!vfox) {
            lines.add(helpLine("E", "[advanced] Edit global mise config.toml in-app"));
        }
        lines.add(helpLine("A", "Activate " + tool
                + " in your shell profile (detects PowerShell, bash, zsh, fish, Nushell)"));
        if (!vfox) {
            lines.add(helpLine("T", "[advanced] Trust this project's mise config (mise trust)"));
            lines.add(helpLine("D", "[advanced] Run mise doctor — full report in the log"));
            lines.add(helpLine("U", "[advanced] " + (ctx.state().selfUpdateDisabled()
                    ? "mise self-update — unavailable, update mise via your package manager"
                    : "mise self-update")));
            lines.add(helpLine("X", "[advanced] Prune unused/old tool versions (asks to confirm)"));
        } else {
            lines.add(helpLine("U", "[advanced] vfox upgrade — update vfox itself to the latest version"));
            lines.add(helpLine("P", "[advanced] Add plugin with explicit name [--alias/--source]"));
        }
        lines.add(helpLine("B", "[advanced] Switch UI backend jline3/panama (now: " + ctx.uiBackend()
                + ") — takes effect on restart"));
        lines.add(helpLine("i", "Install selected tool"));
        lines.add(helpLine("u", "Apply selected tool to project " + configFile));
        lines.add(helpLine("x", "[advanced] Uninstall selected tool (asks to confirm)"));
        lines.add(helpLine("R", "[advanced] Remove selected tool from project " + configFile + " (asks to confirm)"));
        lines.add(helpLine("g", "Install/set as global default"));
        if (!vfox) {
            lines.add(helpLine("p", "Upgrade selected tool to the newest version"));
            lines.add(helpLine("P", "Upgrade all outdated tools (asks to confirm)"));
        }
        if (!vfox) {
            lines.add(helpLine("Enter", "Run selected task"));
            lines.add(helpLine(":", "Run selected task with arguments"));
            lines.add(helpLine(".", "Re-run the last task"));
        }
        lines.add(helpLine("c", "Cancel the selected tool/task (or the only one running)"));
        lines.add(helpLine("C", "Cancel every running operation, from any panel"));
        if (!vfox) {
            lines.add(helpLine("y", "Env panel: copy the selected variable's value"));
        }
        lines.add(helpLine("r", "Refresh"));
        lines.add(helpLine("q", "Quit"));
        lines.add(helpLine("?", "Toggle this help"));
        lines.add(text(""));
        lines.add(text("In the Add SDK modal: type to fuzzy find, Enter to choose").dim());
        lines.add(text(vfox ? "the plugin, then again for the version to install."
                : "the SDK, then again for the version. Ctrl+G = local/global.").dim());
        lines.add(text(""));
        lines.add(text("Config: ~/.config/tambo/tambo.properties (theme.* colours,").dim());
        lines.add(text("keys.* nav overrides). $TAMBO_CONFIG_DIR overrides the path.").dim());
        lines.add(text(""));
        lines.add(text("--offline at launch: shows only installed tools, blocks").dim());
        lines.add(text("install/use" + (vfox ? "/self-update" : "/upgrade/self-update") + "/Add SDK (need the network).").dim());
        lines.add(text(""));
        lines.add(text("[advanced] keys are hidden until V is pressed (or launch with").dim());
        lines.add(text("--advanced-features). The Advanced panel then splits off Details;").dim());
        lines.add(text("↑/↓ + Enter runs one directly, or the letter still works anywhere.").dim());
        lines.add(text(""));
        lines.add(text("Press ? or Esc to close").dim());

        return dialog("Help", lines.toArray(new Element[0]))
                .rounded().borderColor(Color.CYAN).width(64);
    }

    private Element helpLine(String key, String description) {
        return row(text(key).bold().yellow().constraint(length(18)), text(description));
    }
}
