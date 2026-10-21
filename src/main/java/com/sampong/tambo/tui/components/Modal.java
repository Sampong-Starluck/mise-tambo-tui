package com.sampong.tambo.tui.components;

/**
 * A dialog that, while open, takes the keyboard from the panels behind it. Implemented by every
 * modal so {@link com.sampong.tambo.tui.TuiComponents#modals()} can answer "is anything open?"
 * without each caller naming every modal itself.
 */
public interface Modal {

    boolean isOpen();
}
