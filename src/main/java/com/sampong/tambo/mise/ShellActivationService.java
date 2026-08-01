package com.sampong.tambo.mise;

/**
 * Enables {@code mise activate} for future shells by writing the activation line
 * into the user's shell startup file. The target shell (PowerShell/pwsh, bash,
 * zsh, fish, or Nushell) is detected from the running process rather than
 * assumed from the OS, so it activates whichever shell actually launched the
 * app — see {@code ShellActivationServiceImp} for the detection details.
 * Idempotent: does nothing when the startup file already carries the activation.
 */
public interface ShellActivationService {

    /** The result of installing mise activation into the user's shell startup file. */
    record ActivationOutcome(boolean ok, boolean changed, String message) {
    }

    ActivationOutcome activateInShell();
}
