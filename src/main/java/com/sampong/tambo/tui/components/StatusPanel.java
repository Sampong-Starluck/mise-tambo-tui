package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Panel;

import com.sampong.tambo.mise.model.DoctorInfo;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Panel 1 — mise health summary from {@code mise doctor}. */
@RequiredArgsConstructor
public final class StatusPanel {

    @NonNull
    private final UiContext ctx;

    public Panel build() {
        // `mise doctor` is the slow half of this panel, so it is fetched on first
        // render rather than at startup. Until it answers every row below reports
        // "checking…": DoctorInfo.unknown() would otherwise render as a confident
        // "not activated — press A", nagging the user to fix a working setup.
        ctx.actions().ensureDoctor();
        boolean doctorKnown = ctx.state().doctorLazy().everLoaded();
        DoctorInfo doctor = ctx.state().doctor();
        boolean trustKnown = ctx.state().trustLazy().everLoaded();

        List<Element> rows = new ArrayList<>();
        rows.add(row(text("mise    ").dim(),
                doctorKnown ? text(doctor.version()).bold() : pending()));
        rows.add(row(text("ui      ").dim(), text(ctx.uiBackend()).bold()));
        if (ctx.state().offline()) {
            rows.add(row(text("mode    ").dim(), text("OFFLINE").yellow().bold()));
        }
        rows.add(badgeRow("active  ", doctorKnown, doctor.activated(), "  press A to activate"));
        rows.add(badgeRow("trust   ", trustKnown, ctx.state().allTrusted(), "  press T to trust"));
        rows.add(badgeRow("shims   ", doctorKnown, doctor.shimsOnPath(), null));
        rows.add(row(text("configs ").dim(),
                doctorKnown ? text(String.valueOf(doctor.configFileCount())) : pending()));

        return panel("[1] Status", rows.toArray(new Element[0]))
                .id(PanelIds.STATUS).focusable(ctx.modalOpen())
                .rounded()
                .borderColor(ctx.theme().idle())
                .focusedBorderColor(ctx.theme().focus());
    }

    /**
     * A yes/no row that withholds its verdict until the probe behind it has
     * answered, so a value still being fetched never renders as a warning.
     * {@code hint} is the nudge shown next to a "no" badge, or null for none.
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
