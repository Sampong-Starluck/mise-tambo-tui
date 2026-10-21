package com.sampong.tambo.tui.state;

/** Element ids used for focus management and panel selection. */
public final class PanelIds {

    public static final String STATUS = "panel-status";
    public static final String TOOLS = "panel-tools";
    public static final String ENV = "panel-env";
    public static final String TASKS = "panel-tasks";
    public static final String LOG = "panel-log";
    public static final String TOOLS_FILTER = "panel-tools-filter";
    public static final String ENV_FILTER = "panel-env-filter";
    public static final String TASKS_FILTER = "panel-tasks-filter";
    public static final String MODAL_INPUT = "registry-modal-input";
    public static final String CONFIG_EDITOR = "config-editor";
    public static final String TASK_ARGS_INPUT = "task-args-input";
    public static final String ADD_PLUGIN_INPUT = "add-plugin-input";
    /** The Versions column between Tools and the detail pane. */
    public static final String VERSIONS = "panel-versions";
    public static final String VERSIONS_FILTER = "panel-versions-filter";
    public static final String PALETTE_INPUT = "palette-input";
    public static final String COMMAND_LINE_INPUT = "command-line-input";
    /** The full-screen jobs view opened with {@code w}. */
    public static final String JOBS = "panel-jobs";

    private PanelIds() {
    }
}
