package com.sampong.tambo.tui.state;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.sampong.tambo._common.model.AutoInstallStep;
import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.service.SdkVersionBackend;
import com.sampong.tambo.tui.MiseTuiApp;
import com.sampong.tambo.tui.features.AutoInstallPrompt;
import com.sampong.tambo.tui.features.BackendActions;
import com.sampong.tambo.tui.features.Theme;

import org.jspecify.annotations.Nullable;

/**
 * What a panel component is allowed to see of the application: shared state,
 * the actions layer, and focus. Implemented by {@link MiseTuiApp}.
 */
public interface UiContext {

    UiState state();

    BackendActions actions();

    /** The version manager this session is driving. */
    default SdkVersionBackend backend() {
        return state().backend();
    }

    /**
     * Whether the active backend can do {@code feature}. Every surface that used to ask "is
     * this vfox?" asks this instead, so the two backends differ in the UI only where they
     * genuinely differ — and a backend that gains a feature needs no change in any panel.
     */
    default boolean supports(BackendFeature feature) {
        return backend().supports(feature);
    }

    /** The active colour palette; panels read border/highlight colours from it. */
    Theme theme();

    /** The configured TamboUI terminal backend ({@code jline3} or {@code panama}). */
    String uiBackend();

    /** The id of the currently focused element, or null. */
    @Nullable String focusedId();

    void focus(String id);

    void clearFocus();

    /** True while no modal is open (panels leave the focus chain while one is). */
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

    /**
     * Opens the registry modal straight at its version step for {@code tool}, skipping the
     * plugin-picking step. Used by the Tools panel for a vfox plugin that is registered but
     * has no version installed yet: there is no {@code tool@version} to act on, so "install"
     * has to mean "pick a version first".
     */
    void promptVersionFor(String tool);

    /**
     * Walks the user through the near-misses auto-install found — a tool whose installed
     * version and config version differ only within the same major release — collecting one
     * answer per tool. Implements {@link AutoInstallPrompt}, which is how
     * {@link BackendActions#autoInstall} takes it: the action layer owns the plan and the
     * commands, and is handed the way to ask rather than a whole {@code UiContext}.
     *
     * @see com.sampong.tambo.tui.components.AutoInstallModal
     */
    void promptAutoInstall(List<AutoInstallStep> undecided,
                           Consumer<Map<String, String>> onDecided, Runnable onCancel);
}
