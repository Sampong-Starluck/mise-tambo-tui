package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Panel;

import com.sampong.tambo.tui.state.UiContext;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A left-sidebar cheatsheet for the maintenance/config keys that {@link UiContext#state()}
 * gates behind {@code advancedFeatures} — only rendered while that flag is on ({@code V} to
 * toggle, {@code --advanced-features} to start with it already on). Purely informational: the
 * keys it lists still run through the same global/panel handlers as everything else, this just
 * makes them discoverable once unlocked.
 */
@RequiredArgsConstructor
public final class AdvancedPanel {

    @NonNull
    private final UiContext ctx;

    public Panel build() {
        boolean vfox = ctx.state().vfox();
        List<Element> rows = new ArrayList<>();
        if (vfox) {
            rows.add(keyRow("P", "add plugin --alias/--source"));
            rows.add(keyRow("U", "vfox upgrade"));
        } else {
            rows.add(keyRow("T", "trust project config"));
            rows.add(keyRow("E", "edit global config"));
            rows.add(keyRow("D", "mise doctor"));
            rows.add(keyRow("U", "mise self-update"));
            rows.add(keyRow("X", "prune old versions"));
        }
        rows.add(keyRow("x", "uninstall selected tool"));
        rows.add(keyRow("R", "remove tool from config"));
        rows.add(text(""));
        rows.add(text("V hides this panel").dim());

        return panel("Advanced", rows.toArray(new Element[0]))
                .rounded()
                .borderColor(ctx.theme().idle());
    }

    private static Element keyRow(String key, String description) {
        return row(text(" " + key + " ").bold().yellow(), text(description).dim());
    }
}
