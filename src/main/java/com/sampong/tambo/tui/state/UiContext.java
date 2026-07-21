package com.sampong.tambo.tui.state;

import com.sampong.tambo.tui.MiseTuiApp;
import com.sampong.tambo.tui.features.MiseActions;
import com.sampong.tambo.tui.features.Theme;

import org.jspecify.annotations.Nullable;

/**
 * What a panel component is allowed to see of the application: shared state,
 * the actions layer, and focus. Implemented by {@link MiseTuiApp}.
 */
public interface UiContext {

    UiState state();

    MiseActions actions();

    /** The active colour palette; panels read border/highlight colours from it. */
    Theme theme();

    /** The id of the currently focused element, or null. */
    @Nullable String focusedId();

    void focus(String id);

    void clearFocus();

    /** True while the registry modal is open (sidebar panels leave the focus chain). */
    boolean modalOpen();

    /**
     * Opens the shared yes/no confirmation dialog; {@code onConfirm} runs on the
     * render thread only if the user confirms. Lets any panel guard a destructive
     * action without owning its own dialog.
     */
    void confirm(String message, Runnable onConfirm);

    /**
     * Opens the task-arguments prompt for {@code taskName}, seeded with
     * {@code initialArgs}; on Enter it runs {@code mise run <task> -- <args>}.
     */
    void promptTaskArgs(String taskName, String initialArgs);
}
