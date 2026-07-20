package com.sampong.tambo.mise;

/**
 * Enables {@code mise activate} for future shells by writing the activation line
 * into the user's shell startup file. Supported shells:
 * <ul>
 *   <li>Windows — PowerShell / pwsh: appends to the {@code $PROFILE} script
 *       (asked from the shell itself, so OneDrive-redirected Documents folders
 *       resolve correctly)</li>
 *   <li>elsewhere — bash, zsh, and fish, detected from {@code $SHELL}; anything
 *       unrecognized falls back to bash</li>
 * </ul>
 * Idempotent: does nothing when the startup file already mentions {@code mise activate}.
 */
public interface ShellActivationService {

    /** The result of installing mise activation into the user's shell startup file. */
    record ActivationOutcome(boolean ok, boolean changed, String message) {
    }

    ActivationOutcome activateInShell();
}
