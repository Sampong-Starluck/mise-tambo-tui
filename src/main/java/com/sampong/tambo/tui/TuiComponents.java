package com.sampong.tambo.tui;

import com.sampong.tambo.tui.components.AddPluginModal;
import com.sampong.tambo.tui.components.AdvancedPanel;
import com.sampong.tambo.tui.components.ConfigEditorModal;
import com.sampong.tambo.tui.components.ConfirmModal;
import com.sampong.tambo.tui.components.DetailPanel;
import com.sampong.tambo.tui.components.EnvPanel;
import com.sampong.tambo.tui.components.HelpOverlay;
import com.sampong.tambo.tui.components.LogPanel;
import com.sampong.tambo.tui.components.RegistryModal;
import com.sampong.tambo.tui.components.SelectBackendModal;
import com.sampong.tambo.tui.components.StatusPanel;
import com.sampong.tambo.tui.components.SwitchBackendModal;
import com.sampong.tambo.tui.components.TaskArgsModal;
import com.sampong.tambo.tui.components.TasksPanel;
import com.sampong.tambo.tui.components.ToolsPanel;

/**
 * Every panel, modal, and overlay {@link MiseTuiApp} owns, bundled so the sibling
 * {@code layout} and {@code keys} packages can reach them without {@code MiseTuiApp}
 * exposing a getter per component. All fields are built once in {@code MiseTuiApp}'s
 * constructor and never change identity afterward — only what they render does.
 */
public record TuiComponents(
        StatusPanel statusPanel,
        ToolsPanel toolsPanel,
        EnvPanel envPanel,
        TasksPanel tasksPanel,
        DetailPanel detailPanel,
        LogPanel logPanel,
        AdvancedPanel advancedPanel,
        RegistryModal registryModal,
        ConfigEditorModal configEditor,
        ConfirmModal confirmModal,
        TaskArgsModal taskArgsModal,
        AddPluginModal addPluginModal,
        HelpOverlay helpOverlay,
        SelectBackendModal selectBackendModal,
        SwitchBackendModal switchBackendModal) {
}
