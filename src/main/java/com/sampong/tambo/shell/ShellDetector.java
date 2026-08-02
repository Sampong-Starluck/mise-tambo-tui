package com.sampong.tambo.shell;

import java.nio.file.Path;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Identifies the shell this app is actually running under: first by walking up the process
 * tree looking for a recognizable shell executable (the direct signal — this is the shell the
 * user typed the launch command into), then falling back to environment heuristics if that's
 * inconclusive (e.g. the process tree isn't readable, or a launcher script sits in between).
 * Shared by every backend's shell-activation implementation — detection doesn't depend on
 * which version manager is asking.
 */
public final class ShellDetector {

    private ShellDetector() {
    }

    public static Shell detectShell() {
        Shell fromProcessTree = shellFromProcessTree();
        return fromProcessTree != null ? fromProcessTree : shellFromEnvironment();
    }

    /**
     * Walks up to a few hops of ancestor processes so an intermediate launcher — a wrapper
     * script, or the version manager itself when started via its own task runner — doesn't
     * hide the real shell one or two generations up.
     */
    private static @Nullable Shell shellFromProcessTree() {
        ProcessHandle handle = ProcessHandle.current().parent().orElse(null);
        for (int hop = 0; handle != null && hop < 5; hop++) {
            Shell shell = handle.info().command().map(ShellDetector::shellFromExecutable).orElse(null);
            if (shell != null) {
                return shell;
            }
            handle = handle.parent().orElse(null);
        }
        return null;
    }

    private static @Nullable Shell shellFromExecutable(String command) {
        String name = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return switch (name) {
            case "nu" -> Shell.NU;
            case "bash" -> Shell.BASH;
            case "zsh" -> Shell.ZSH;
            case "fish" -> Shell.FISH;
            case "pwsh" -> Shell.PWSH;
            case "powershell" -> Shell.POWERSHELL;
            default -> null;
        };
    }

    private static Shell shellFromEnvironment() {
        if (System.getenv("NU_VERSION") != null) {
            return Shell.NU;
        }
        String shellEnv = System.getenv("SHELL");
        if (shellEnv != null && !shellEnv.isBlank()) {
            Shell shell = shellFromExecutable(shellEnv);
            if (shell != null) {
                return shell;
            }
        }
        return ShellFileWriter.isWindows() ? Shell.PWSH : Shell.BASH;
    }
}
