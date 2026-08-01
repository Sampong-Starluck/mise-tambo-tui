package com.sampong.tambo.mise.implement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.sampong.tambo.mise.ShellActivationService;

import org.jspecify.annotations.Nullable;

import lombok.extern.slf4j.Slf4j;

/**
 * Enables {@code mise activate} for future shells by writing the activation line
 * into the user's shell startup file. The target shell is detected rather than
 * assumed from the OS — see {@link #detectShell()} — so a Windows machine that's
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
@Slf4j
@Service
public class ShellActivationServiceImp implements ShellActivationService {

    private static final String COMMENT = "# Added by tambo - activate mise";

    @Override
    public ActivationOutcome activateInShell() {
        Shell shell = detectShell();
        log.debug("Detected shell: {}", shell);
        return switch (shell) {
            case NU -> activateNu();
            case PWSH -> activatePowerShell("pwsh");
            case POWERSHELL -> activatePowerShell("powershell");
            case ZSH -> activatePosixShell("zsh",
                    Path.of(home(), ".zshrc"), "eval \"$(mise activate zsh)\"");
            case FISH -> activatePosixShell("fish",
                    Path.of(home(), ".config", "fish", "config.fish"), "mise activate fish | source");
            case BASH -> activatePosixShell("bash",
                    Path.of(home(), ".bashrc"), "eval \"$(mise activate bash)\"");
        };
    }

    // ==================== Shell detection ====================

    private enum Shell { BASH, ZSH, FISH, NU, PWSH, POWERSHELL }

    /**
     * Identifies the shell this app is actually running under: first by walking
     * up the process tree looking for a recognizable shell executable (the direct
     * signal — this is the shell the user typed the launch command into), then
     * falling back to environment heuristics if that's inconclusive (e.g. the
     * process tree isn't readable, or a launcher script sits in between).
     */
    private Shell detectShell() {
        Shell fromProcessTree = shellFromProcessTree();
        return fromProcessTree != null ? fromProcessTree : shellFromEnvironment();
    }

    /**
     * Walks up to a few hops of ancestor processes so an intermediate launcher —
     * a wrapper script, or {@code mise} itself when started via {@code mise run} —
     * doesn't hide the real shell one or two generations up.
     */
    private @Nullable Shell shellFromProcessTree() {
        ProcessHandle handle = ProcessHandle.current().parent().orElse(null);
        for (int hop = 0; handle != null && hop < 5; hop++) {
            Shell shell = handle.info().command().map(ShellActivationServiceImp::shellFromExecutable).orElse(null);
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

    private Shell shellFromEnvironment() {
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
        return isWindows() ? Shell.PWSH : Shell.BASH;
    }

    // ==================== PowerShell / pwsh ====================

    private ActivationOutcome activatePowerShell(String detected) {
        Path profile = powerShellProfilePath(detected);
        String line = "(&mise activate pwsh) | Out-String | Invoke-Expression";
        return appendActivationLine(profile, line, COMMENT, "mise activate",
                "restart " + (detected.equals("pwsh") ? "PowerShell (pwsh)" : "PowerShell") + " to finish");
    }

    /**
     * Asks PowerShell for its {@code $PROFILE} path, querying the detected flavor
     * first (pwsh vs Windows PowerShell 5 have different profile files) and
     * falling back to the other, then to the conventional location if neither
     * answers.
     */
    private Path powerShellProfilePath(String detected) {
        String[] order = "powershell".equals(detected)
                ? new String[]{"powershell", "pwsh"}
                : new String[]{"pwsh", "powershell"};
        for (String shell : order) {
            String path = queryProcessOutput(shell, "-NoProfile", "-Command", "Write-Output $PROFILE");
            if (path != null && !path.isBlank()) {
                return Path.of(path.strip());
            }
        }
        return Path.of(System.getProperty("user.home"),
                "Documents", "PowerShell", "Microsoft.PowerShell_profile.ps1");
    }

    // ==================== bash / zsh / fish ====================

    private ActivationOutcome activatePosixShell(String shellName, Path rc, String line) {
        return appendActivationLine(rc, line, COMMENT, "mise activate",
                "restart your " + shellName + " shell to finish");
    }

    // ==================== Nushell ====================

    /**
     * Nushell can't {@code eval}, so mise's recipe is two files instead of one
     * line: {@code env.nu} regenerates {@code mise.nu} on every startup, and
     * {@code config.nu} sources it via {@code use}. Both paths are asked from
     * {@code nu} itself since they're configurable.
     */
    private ActivationOutcome activateNu() {
        Path envFile = nuFilePath("$nu.env-path", "env.nu");
        Path configFile = nuFilePath("$nu.config-path", "config.nu");
        String restartHint = "restart Nushell to finish";

        String envSnippet = "let mise_path = $nu.default-config-dir | path join mise.nu"
                + System.lineSeparator() + "^mise activate nu | save $mise_path --force";
        ActivationOutcome envOutcome = appendActivationLine(envFile, envSnippet, COMMENT,
                "mise activate nu", restartHint);
        if (!envOutcome.ok()) {
            return envOutcome;
        }

        String configSnippet = "use ($nu.default-config-dir | path join mise.nu)";
        ActivationOutcome configOutcome = appendActivationLine(configFile, configSnippet, COMMENT,
                "mise.nu", restartHint);
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

    /** Asks {@code nu} for one of its {@code $nu.*} path variables, falling back to the conventional location. */
    private Path nuFilePath(String nuVariable, String conventionalFileName) {
        String queried = queryProcessOutput("nu", "-c", "print (" + nuVariable + ")");
        if (queried != null && !queried.isBlank()) {
            return Path.of(queried.strip());
        }
        Path configDir = isWindows()
                ? Path.of(envOr("APPDATA", System.getProperty("user.home")), "nushell")
                : Path.of(System.getProperty("user.home"), ".config", "nushell");
        return configDir.resolve(conventionalFileName);
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    // ==================== Shared plumbing ====================

    private static String home() {
        return System.getProperty("user.home");
    }

    /** Runs {@code command args…} and returns its trimmed stdout, or null if it couldn't be run or failed. */
    private @Nullable String queryProcessOutput(String command, String... args) {
        try {
            List<String> full = new ArrayList<>(List.of(command));
            full.addAll(List.of(args));
            Process process = new ProcessBuilder(full)
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
            log.debug("{} not available: {}", command, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private ActivationOutcome appendActivationLine(
            Path file, String content, String comment, String idempotencyMarker, String restartHint) {
        try {
            if (Files.exists(file) && Files.readString(file).contains(idempotencyMarker)) {
                return new ActivationOutcome(true, false,
                        "mise activation already present in " + file + " — restart your shell if it isn't active yet");
            }
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, System.lineSeparator() + comment + System.lineSeparator()
                            + content + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return new ActivationOutcome(true, true,
                    "Added mise activation to " + file + " — " + restartHint);
        } catch (IOException e) {
            return new ActivationOutcome(false, false, "Could not update " + file + ": " + e.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
