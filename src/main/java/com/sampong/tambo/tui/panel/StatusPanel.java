package com.sampong.tambo.tui.panel;

import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.Panel;

import com.sampong.tambo.mise.model.DoctorInfo;
import com.sampong.tambo.tui.PanelIds;
import com.sampong.tambo.tui.Ui;
import com.sampong.tambo.tui.UiContext;

/** Panel 1 — mise health summary from {@code mise doctor}. */
public final class StatusPanel {

    private final UiContext ctx;

    public StatusPanel(UiContext ctx) {
        this.ctx = ctx;
    }

    public Panel build() {
        DoctorInfo doctor = ctx.state().doctor();
        return panel("1 Status",
                row(text("mise    ").dim(), text(doctor.version()).bold()),
                doctor.activated()
                        ? row(text("active  ").dim(), Ui.badge(true))
                        : row(text("active  ").dim(), Ui.badge(false), text("  press A to activate").yellow()),
                row(text("shims   ").dim(), Ui.badge(doctor.shimsOnPath())),
                row(text("configs ").dim(), text(String.valueOf(doctor.configFileCount())))
        ).id(PanelIds.STATUS).focusable(ctx.modalOpen())
                .rounded()
                .borderColor(Color.DARK_GRAY)
                .focusedBorderColor(Color.GREEN);
    }
}
