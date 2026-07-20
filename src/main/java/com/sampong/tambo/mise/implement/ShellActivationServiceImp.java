package com.sampong.tambo.mise.implement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.sampong.tambo.mise.ShellActivationService;

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
@Service
public class ShellActivationServiceImp implements ShellActivationService {

    @Override
    public ActivationOutcome activateInShell() {
        return isWindows() ? activatePowerShell() : activatePosixShell();
    }

    // ==================== Windows / PowerShell ====================

    private ActivationOutcome activatePowerShell() {
        Path profile = powerShellProfilePath();
        String line = "(&mise activate pwsh) | Out-String | Invoke-Expression";
        return appendActivationLine(profile, line, "# Added by tambo - activate mise",
                "restart PowerShell to finish");
    }

    /**
     * Asks PowerShell for its {@code $PROFILE} path — pwsh (PowerShell 7+) first,
     * then Windows PowerShell 5 — falling back to the conventional location when
     * neither answers.
     */
    private Path powerShellProfilePath() {
        for (String shell : new String[]{"pwsh", "powershell"}) {
            String path = queryProfile(shell);
            if (path != null && !path.isBlank()) {
                return Path.of(path.strip());
            }
        }
        return Path.of(System.getProperty("user.home"),
                "Documents", "PowerShell", "Microsoft.PowerShell_profile.ps1");
    }

    private String queryProfile(String shell) {
        try {
            Process process = new ProcessBuilder(shell, "-NoProfile", "-Command", "Write-Output $PROFILE")
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null; // shell not installed / not on PATH
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ==================== bash / zsh / fish ====================

    private ActivationOutcome activatePosixShell() {
        String home = System.getProperty("user.home");
        String shellEnv = System.getenv("SHELL");
        String shell = (shellEnv == null || shellEnv.isBlank())
                ? "bash"
                : Path.of(shellEnv).getFileName().toString();

        Path rc;
        String line;
        switch (shell) {
            case "zsh" -> {
                rc = Path.of(home, ".zshrc");
                line = "eval \"$(mise activate zsh)\"";
            }
            case "fish" -> {
                rc = Path.of(home, ".config", "fish", "config.fish");
                line = "mise activate fish | source";
            }
            default -> {
                shell = "bash";
                rc = Path.of(home, ".bashrc");
                line = "eval \"$(mise activate bash)\"";
            }
        }
        return appendActivationLine(rc, line, "# Added by tambo — activate mise",
                "restart your " + shell + " shell to finish");
    }

    // ==================== Shared plumbing ====================

    private ActivationOutcome appendActivationLine(Path file, String line, String comment, String restartHint) {
        try {
            if (Files.exists(file) && Files.readString(file).contains("mise activate")) {
                return new ActivationOutcome(true, false,
                        "mise activation already present in " + file + " — restart your shell if it isn't active yet");
            }
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, System.lineSeparator() + comment + System.lineSeparator()
                            + line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return new ActivationOutcome(true, true,
                    "Added \"" + line + "\" to " + file + " — " + restartHint);
        } catch (IOException e) {
            return new ActivationOutcome(false, false, "Could not update " + file + ": " + e.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
