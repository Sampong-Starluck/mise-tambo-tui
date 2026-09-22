package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Panel;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Panel 1 — what the active backend reports about itself.
 * <p>
 * One renderer for both backends rather than the mise/vfox pair this used to keep: the rows every
 * backend can answer (version, UI backend, offline mode) are always shown, and the health rows are
 * added only when the backend actually reports health. A backend with no health report shows fewer
 * rows, not wrong ones — "not activated" for something with no concept of activation was a warning
 * about nothing.
 * <p>
 * The only panel in the stack sized to its content rather than given a share of the sidebar (see
 * {@link SidePanels#constraint}): its body is a fixed handful of rows, so a weight would stretch
 * six lines of text over a third of the screen. Focusing it fills the main pane with the full
 * capability report instead.
 */
@RequiredArgsConstructor
public final class StatusPanel {

    @NonNull
    private final UiContext ctx;

    public Panel build() {
        return panel(SidePanels.title(SidePanels.Side.STATUS, ""),
                column(rows().toArray(new Element[0])))
                .id(PanelIds.STATUS).focusable(ctx.modalOpen())
                .rounded()
                .borderColor(ctx.theme().idle())
                .focusedBorderColor(ctx.theme().focus());
    }

    /**
     * How many rows {@link #build()} will render, which is what {@link SidePanels} sizes this
     * panel by. Derived by building the rows rather than by a second copy of the same
     * conditions — the elements are a handful of text spans and the health probe behind them is
     * a {@code Lazy}, so asking twice in a frame costs nothing and cannot drift out of step.
     */
    public int rowCount() {
        return rows().size();
    }

    private List<Element> rows() {
        // The health report is the slow half of this panel, so it is fetched on first render
        // rather than at startup. Until it answers every row reports "checking…": the unknown
        // placeholder would otherwise render as a confident "not activated — press A",
        // nagging the user to fix a working setup.
        ctx.actions().ensureBackendInfo();
        boolean infoKnown = ctx.state().backendInfoLazy().everLoaded();
        BackendInfo info = ctx.state().backendInfo();

        List<Element> rows = new ArrayList<>();
        rows.add(row(text(label(ctx.backend().name())).dim(),
                infoKnown ? text(info.version()).bold() : pending()));
        rows.add(row(text(label("ui")).dim(), text(ctx.uiBackend()).bold()));
        if (ctx.state().offline()) {
            rows.add(row(text(label("mode")).dim(), text("OFFLINE").yellow().bold()));
        }

        if (infoKnown && info.reportsHealth()) {
            rows.add(badgeRow(label("active"), true, info.activated(), "  press A to activate"));
            rows.add(badgeRow(label("shims"), true, info.shimsOnPath(), null));
            rows.add(row(text(label("configs")).dim(), text(String.valueOf(info.configFileCount()))));
        } else if (!infoKnown && ctx.supports(BackendFeature.DOCTOR)) {
            // Keep the rows the report will fill reserved while it loads, so the panel does
            // not visibly grow a few hundred milliseconds in.
            rows.add(badgeRow(label("active"), false, false, null));
            rows.add(badgeRow(label("shims"), false, false, null));
            rows.add(row(text(label("configs")).dim(), pending()));
        }

        if (ctx.supports(BackendFeature.TRUST)) {
            rows.add(badgeRow(label("trust"), ctx.state().trustLazy().everLoaded(),
                    ctx.state().allTrusted(), "  press T to trust"));
        }
        return rows;
    }

    /** Pads a row label to a constant width so the values line up in one column. */
    private static String label(String text) {
        return Ui.fixedWidth(text, 8);
    }

    /**
     * A yes/no row that withholds its verdict until the probe behind it has answered, so a
     * value still being fetched never renders as a warning. {@code hint} is the nudge shown
     * next to a "no" badge, or null for none.
     */
    private static Element badgeRow(String label, boolean known, boolean value, @Nullable String hint) {
        if (!known) {
            return row(text(label).dim(), pending());
        }
        if (value) {
            return row(text(label).dim(), Ui.badge(true));
        }
        return hint == null
                ? row(text(label).dim(), Ui.badge(false))
                : row(text(label).dim(), Ui.badge(false), text(hint).yellow());
    }

    private static Element pending() {
        return text("checking…").dim();
    }
}
