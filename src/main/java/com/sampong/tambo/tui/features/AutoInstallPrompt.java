package com.sampong.tambo.tui.features;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.sampong.tambo._common.model.AutoInstallStep;

/**
 * How the auto-install flow asks about the near-misses it found.
 * <p>
 * {@link BackendActions} owns the plan and the commands but no modal — it has the executor and
 * the render-thread hop, not a {@code UiContext}. Rather than give it one (every other action
 * there runs without asking anything), the caller hands in the way to ask, the same way the
 * Tools panel hands {@code ctx::confirm} to a destructive action. The implementation in the app
 * is {@code MiseTuiApp#promptAutoInstall}, which drives {@code AutoInstallModal}; a caller with
 * nothing to ask with can pass one that skips straight to {@code onDecided} with an empty map.
 */
@FunctionalInterface
public interface AutoInstallPrompt {

    /**
     * Asks the user, one tool at a time, which version to apply for each step whose config
     * version and installed version differ.
     *
     * @param undecided every {@link AutoInstallStep.Status#SIMILAR} step, or offline every
     *                  {@link AutoInstallStep.Status#SUBSTITUTE} one, in config order
     * @param onDecided run on the render thread once all of them are answered, with the chosen
     *                  version per SDK name — a tool the user skipped is simply absent from the
     *                  map rather than present with a null
     * @param onCancel  run on the render thread if the user backs out; the whole auto-install
     *                  is abandoned, including the installs that needed no question
     */
    void ask(List<AutoInstallStep> undecided, Consumer<Map<String, String>> onDecided, Runnable onCancel);
}
