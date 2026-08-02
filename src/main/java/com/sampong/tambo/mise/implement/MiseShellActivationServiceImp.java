package com.sampong.tambo.mise.implement;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import com.sampong.tambo.mise.ShellActivationService;
import com.sampong.tambo._common.model.Shell;
import com.sampong.tambo._common.util.ShellDetector;
import com.sampong.tambo._common.util.ShellFileWriter;

/**
 * Enables {@code mise activate} for future shells by writing the activation line
 * into the user's shell startup file. The target shell is detected rather than
 * assumed from the OS — see {@link ShellDetector} — so a Windows machine that's
 * currently running Git Bash or Nushell gets activated correctly instead of
 * always landing on PowerShell. Supported shells:
 * <ul>
 *   <li>PowerShell / pwsh: appends to the {@code $PROFILE} script (asked from
 *       the shell itself, so OneDrive-redirected Documents folders resolve
 *       correctly)</li>
 *   <li>bash, zsh, fish: appends {@code eval "$(mise activate <shell>)"} (or the
 *       fish {@code | source} equivalent) to the shell's rc file</li>
 *   <li>Nushell: cannot {@code eval}, so mise's own two-file recipe is used —
 *       {@code env.nu} regenerates {@code mise.nu} on every startup and
 *       {@code config.nu} sources it — with paths asked from {@code nu} itself</li>
 * </ul>
 * Idempotent: does nothing when a file already carries the relevant marker.
 */
@Service
public class MiseShellActivationServiceImp implements ShellActivationService {

    private static final String COMMENT = "# Added by tambo - activate mise";

    @Override
    public ActivationOutcome activateInShell() {
        Shell shell = ShellDetector.detectShell();
        return switch (shell) {
            case NU -> activateNu();
            case PWSH -> activatePowerShell("pwsh");
            case POWERSHELL -> activatePowerShell("powershell");
            case ZSH -> activatePosixShell(
                    Path.of(ShellFileWriter.home(), ".zshrc"), "eval \"$(mise activate zsh)\"");
            case FISH -> activatePosixShell(
                    Path.of(ShellFileWriter.home(), ".config", "fish", "config.fish"), "mise activate fish | source");
            case BASH -> activatePosixShell(
                    Path.of(ShellFileWriter.home(), ".bashrc"), "eval \"$(mise activate bash)\"");
        };
    }

    private ActivationOutcome activatePowerShell(String detected) {
        Path profile = ShellFileWriter.powerShellProfilePath(detected);
        String line = "(&mise activate pwsh) | Out-String | Invoke-Expression";
        return ShellFileWriter.appendActivationLine(profile, line, COMMENT, "mise activate",
                "restart " + (detected.equals("pwsh") ? "PowerShell (pwsh)" : "PowerShell") + " to finish", "mise");
    }

    private ActivationOutcome activatePosixShell(Path rc, String line) {
        return ShellFileWriter.appendActivationLine(rc, line, COMMENT, "mise activate",
                "restart your shell to finish", "mise");
    }

    /**
     * Nushell can't {@code eval}, so mise's recipe is two files instead of one line:
     * {@code env.nu} regenerates {@code mise.nu} on every startup, and {@code config.nu}
     * sources it via {@code use}. Both paths are asked from {@code nu} itself since they're
     * configurable.
     */
    private ActivationOutcome activateNu() {
        Path envFile = ShellFileWriter.nuFilePath("$nu.env-path", "env.nu");
        Path configFile = ShellFileWriter.nuFilePath("$nu.config-path", "config.nu");
        String restartHint = "restart Nushell to finish";

        String envSnippet = "let mise_path = $nu.default-config-dir | path join mise.nu"
                + System.lineSeparator() + "^mise activate nu | save $mise_path --force";
        ActivationOutcome envOutcome = ShellFileWriter.appendActivationLine(envFile, envSnippet, COMMENT,
                "mise activate nu", restartHint, "mise");
        if (!envOutcome.ok()) {
            return envOutcome;
        }

        String configSnippet = "use ($nu.default-config-dir | path join mise.nu)";
        ActivationOutcome configOutcome = ShellFileWriter.appendActivationLine(configFile, configSnippet, COMMENT,
                "mise.nu", restartHint, "mise");
        if (!configOutcome.ok()) {
            return configOutcome;
        }

        if (!envOutcome.changed() && !configOutcome.changed()) {
            return new ActivationOutcome(true, false,
                    "mise activation already present in " + envFile + " and " + configFile
                            + " — restart Nushell if it isn't active yet");
        }
        return new ActivationOutcome(true, true,
                "Added mise activation to " + envFile + " and " + configFile + " — " + restartHint);
    }
}
