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

        List<Element> rows = new ArrayList<>();
        rows.add(row(text("mise    ").dim(), text(doctor.version()).bold()));
        if (ctx.state().offline()) {
            rows.add(row(text("mode    ").dim(), text("OFFLINE").yellow().bold()));
        }
        rows.add(doctor.activated()
                ? row(text("active  ").dim(), Ui.badge(true))
                : row(text("active  ").dim(), Ui.badge(false), text("  press A to activate").yellow()));
        rows.add(trusted
                ? row(text("trust   ").dim(), Ui.badge(true))
                : row(text("trust   ").dim(), Ui.badge(false), text("  press T to trust").yellow()));
        rows.add(row(text("shims   ").dim(), Ui.badge(doctor.shimsOnPath())));
        rows.add(row(text("configs ").dim(), text(String.valueOf(doctor.configFileCount()))));

        return panel("1 Status", rows.toArray(new Element[0]))
                .id(PanelIds.STATUS).focusable(ctx.modalOpen())
                .rounded()
                .borderColor(ctx.theme().idle())
                .focusedBorderColor(ctx.theme().focus());
    }
}
