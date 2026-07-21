package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.toolkit.elements.Panel;

import com.sampong.tambo.mise.model.DoctorInfo;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Panel 1 — mise health summary from {@code mise doctor}. */
@RequiredArgsConstructor
public final class StatusPanel {

    @NonNull
    private final UiContext ctx;

    public Panel build() {
        DoctorInfo doctor = ctx.state().doctor();
        boolean trusted = ctx.state().allTrusted();
        return panel("1 Status",
                row(text("mise    ").dim(), text(doctor.version()).bold()),
                doctor.activated()
                        ? row(text("active  ").dim(), Ui.badge(true))
                        : row(text("active  ").dim(), Ui.badge(false), text("  press A to activate").yellow()),
                trusted
                        ? row(text("trust   ").dim(), Ui.badge(true))
                        : row(text("trust   ").dim(), Ui.badge(false), text("  press T to trust").yellow()),
                row(text("shims   ").dim(), Ui.badge(doctor.shimsOnPath())),
                row(text("configs ").dim(), text(String.valueOf(doctor.configFileCount())))
        ).id(PanelIds.STATUS).focusable(ctx.modalOpen())
                .rounded()
                .borderColor(ctx.theme().idle())
                .focusedBorderColor(ctx.theme().focus());
    }
}
