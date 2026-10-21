package com.sampong.tambo.tui;

import java.util.List;

import com.sampong.tambo.tui.components.AddPluginModal;
import com.sampong.tambo.tui.components.BatchConfirmModal;
import com.sampong.tambo.tui.components.CommandLineBar;
import com.sampong.tambo.tui.components.CommandPalette;
import com.sampong.tambo.tui.components.ConfigEditorModal;
import com.sampong.tambo.tui.components.ConfirmModal;
import com.sampong.tambo.tui.components.DetailPanel;
import com.sampong.tambo.tui.components.EnvPanel;
import com.sampong.tambo.tui.components.HelpOverlay;
import com.sampong.tambo.tui.components.JobsView;
import com.sampong.tambo.tui.components.LeaderMenu;
import com.sampong.tambo.tui.components.LogPanel;
import com.sampong.tambo.tui.components.MenuBar;
import com.sampong.tambo.tui.components.Modal;
import com.sampong.tambo.tui.components.RegistryModal;
import com.sampong.tambo.tui.components.SelectBackendModal;
import com.sampong.tambo.tui.components.SidePanels;
import com.sampong.tambo.tui.components.StatusPanel;
import com.sampong.tambo.tui.components.SwitchBackendModal;
import com.sampong.tambo.tui.components.TaskArgsModal;
import com.sampong.tambo.tui.components.TasksPanel;
import com.sampong.tambo.tui.components.ToolsPanel;
import com.sampong.tambo.tui.components.VersionsPanel;

/**
 * Every panel, modal, and overlay {@link MiseTuiApp} owns, bundled so the sibling
 * {@code layout} and {@code keys} packages can reach them without {@code MiseTuiApp}
 * exposing a getter per component. All fields are built once in {@code MiseTuiApp}'s
 * constructor and never change identity afterward — only what they render does.
 */
public record TuiComponents(
        StatusPanel statusPanel,
        ToolsPanel toolsPanel,
        VersionsPanel versionsPanel,
        EnvPanel envPanel,
        TasksPanel tasksPanel,
        DetailPanel detailPanel,
        LogPanel logPanel,
        SidePanels sidePanels,
        RegistryModal registryModal,
        ConfigEditorModal configEditor,
        ConfirmModal confirmModal,
        BatchConfirmModal batchConfirm,
        TaskArgsModal taskArgsModal,
        AddPluginModal addPluginModal,
        HelpOverlay helpOverlay,
        SelectBackendModal selectBackendModal,
        SwitchBackendModal switchBackendModal,
        CommandPalette palette,
        LeaderMenu leaderMenu,
        CommandLineBar commandLine,
        JobsView jobsView,
        MenuBar menuBar) {

    /**
     * Every modal above. Kept here, next to the components themselves, so adding a modal and
     * listing it happen in the same place — a modal missing from this list would leave the
     * panels behind it focusable while it is open.
     */
    public List<Modal> modals() {
        return List.of(registryModal, configEditor, confirmModal, batchConfirm, taskArgsModal, addPluginModal,
                selectBackendModal, switchBackendModal, palette, leaderMenu, commandLine, jobsView, menuBar);
    }
}
