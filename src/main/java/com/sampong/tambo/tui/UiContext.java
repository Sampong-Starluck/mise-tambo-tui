package com.sampong.tambo.tui;

/**
 * What a panel component is allowed to see of the application: shared state,
 * the actions layer, and focus. Implemented by {@link MiseTuiApp}.
 */
public interface UiContext {

    UiState state();

    MiseActions actions();

    /** The id of the currently focused element, or null. */
    String focusedId();

    void focus(String id);

    void clearFocus();

    /** True while the registry modal is open (sidebar panels leave the focus chain). */
    boolean modalOpen();
}
