package com.sampong.tambo.vfox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;

import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo.mise.ShellActivationService;
import com.sampong.tambo._common.model.Shell;
import com.sampong.tambo._common.util.ShellDetector;
import com.sampong.tambo._common.util.ShellFileWriter;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Enables {@code vfox activate} for future shells by writing the activation line into the
 * user's shell startup file, mirroring {@link com.sampong.tambo.mise.implement.MiseShellActivationServiceImp}
 * but with vfox's own per-shell commands (per
 * <a href="https://vfox.dev/guides/quick-start.html">vfox's quick-start guide</a>):
 * <ul>
 *   <li>bash, zsh: {@code eval "$(vfox activate <shell>)"} in the shell's rc file</li>
 *   <li>fish: {@code vfox activate fish | source} in {@code config.fish}</li>
 *   <li>PowerShell / pwsh: {@code Invoke-Expression "$(vfox activate pwsh)"} appended to
 *       {@code $PROFILE}</li>
 *   <li>Nushell: unlike mise's regenerate-on-every-startup recipe, vfox's own docs have you
 *       run {@code vfox activate nushell <config-dir>} once and append its output directly into
 *       {@code config.nu} — done here by invoking vfox and writing what it prints</li>
 * </ul>
 * Clink/Cmder support (copying a {@code clink_vfox.lua} file from vfox's repo) is intentionally
 * not implemented — mise has no Clink support either, so this stays at parity.
 * Idempotent: does nothing when a file already carries the relevant marker.
 */
@Service
@RequiredArgsConstructor
public class VfoxShellActivationServiceImp implements ShellActivationService {

    private static final String COMMENT = "# Added by tambo - activate vfox";
    private static final Duration NU_ACTIVATE_TIMEOUT = Duration.ofSeconds(15);

    @NonNull
    private final VfoxCli cli;

    @Override
    @NullMarked
    public ActivationOutcome activateInShell() {
        Shell shell = ShellDetector.detectShell();
        return switch (shell) {
            case NU -> activateNu();
            case PWSH -> activatePowerShell("pwsh");
            case POWERSHELL -> activatePowerShell("powershell");
            case ZSH -> activatePosixShell(
                    Path.of(ShellFileWriter.home(), ".zshrc"), "eval \"$(vfox activate zsh)\"");
            case FISH -> activatePosixShell(
                    Path.of(ShellFileWriter.home(), ".config", "fish", "config.fish"), "vfox activate fish | source");
            case BASH -> activatePosixShell(
                    Path.of(ShellFileWriter.home(), ".bashrc"), "eval \"$(vfox activate bash)\"");
        };
    }

    private ActivationOutcome activatePowerShell(String detected) {
        Path profile = ShellFileWriter.powerShellProfilePath(detected);
        String line = "Invoke-Expression \"$(vfox activate pwsh)\"";
        return ShellFileWriter.appendActivationLine(profile, line, COMMENT, "vfox activate",
                "restart " + (detected.equals("pwsh") ? "PowerShell (pwsh)" : "PowerShell") + " to finish", "vfox");
    }

    private ActivationOutcome activatePosixShell(Path rc, String line) {
        return ShellFileWriter.appendActivationLine(rc, line, COMMENT, "vfox activate",
                "restart your shell to finish", "vfox");
    }

    /**
     * vfox has no eval-on-startup recipe for Nushell; instead {@code vfox activate nushell
     * <config-dir>} prints the init script to append into {@code config.nu} once. Since the
     * printed script has no stable substring to match on, {@link #COMMENT} itself is the
     * idempotency marker.
     */
    private ActivationOutcome activateNu() {
        Path configFile = ShellFileWriter.nuFilePath("$nu.config-path", "config.nu");
        String configDir = nuConfigDir();
        CliResult result = cli.run(List.of("activate", "nushell", configDir), NU_ACTIVATE_TIMEOUT);
        if (!result.ok() || result.stdout().isBlank()) {
            return new ActivationOutcome(false, false, "vfox activate nushell failed: " + result.summaryLine());
        }
        return ShellFileWriter.appendActivationLine(configFile, result.stdout().stripTrailing(), COMMENT, COMMENT,
                "restart Nushell to finish", "vfox");
    }

    private static String nuConfigDir() {
        String queried = ShellFileWriter.queryProcessOutput("nu", "-c", "print ($nu.default-config-dir)");
        if (queried != null && !queried.isBlank()) {
            return queried.strip();
        }
        return ShellFileWriter.isWindows()
                ? Path.of(ShellFileWriter.envOr("APPDATA", ShellFileWriter.home()), "nushell").toString()
                : Path.of(ShellFileWriter.home(), ".config", "nushell").toString();
    }
}
