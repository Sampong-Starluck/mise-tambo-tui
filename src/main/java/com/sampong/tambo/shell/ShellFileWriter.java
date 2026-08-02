package com.sampong.tambo.shell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.sampong.tambo.mise.ShellActivationService.ActivationOutcome;

import org.jspecify.annotations.Nullable;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared file-IO and process-query plumbing behind every backend's shell activation: appending
 * an activation line/snippet to a shell's startup file (idempotently), and asking a shell for
 * its own profile/config path rather than assuming a conventional location. No knowledge of
 * which version manager is doing the activating — that's supplied by the caller as plain text.
 */
@Slf4j
public final class ShellFileWriter {

    private ShellFileWriter() {
    }

    /** Runs {@code command args…} and returns its trimmed stdout, or null if it couldn't be run or failed. */
    public static @Nullable String queryProcessOutput(String command, String... args) {
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

    /**
     * Asks PowerShell for its {@code $PROFILE} path, querying the detected flavor first (pwsh
     * vs Windows PowerShell 5 have different profile files) and falling back to the other, then
     * to the conventional location if neither answers.
     */
    public static Path powerShellProfilePath(String detected) {
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

    /** Asks {@code nu} for one of its {@code $nu.*} path variables, falling back to the conventional location. */
    public static Path nuFilePath(String nuVariable, String conventionalFileName) {
        String queried = queryProcessOutput("nu", "-c", "print (" + nuVariable + ")");
        if (queried != null && !queried.isBlank()) {
            return Path.of(queried.strip());
        }
        Path configDir = isWindows()
                ? Path.of(envOr("APPDATA", System.getProperty("user.home")), "nushell")
                : Path.of(System.getProperty("user.home"), ".config", "nushell");
        return configDir.resolve(conventionalFileName);
    }

    public static String home() {
        return System.getProperty("user.home");
    }

    public static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Appends {@code content} to {@code file} under a {@code comment} marker, unless
     * {@code idempotencyMarker} is already present in the file. {@code toolName} ("mise" or
     * "vfox") only decorates the returned message.
     */
    public static ActivationOutcome appendActivationLine(Path file, String content, String comment,
                                                          String idempotencyMarker, String restartHint,
                                                          String toolName) {
        try {
            if (Files.exists(file) && Files.readString(file).contains(idempotencyMarker)) {
                return new ActivationOutcome(true, false,
                        toolName + " activation already present in " + file
                                + " — restart your shell if it isn't active yet");
            }
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, System.lineSeparator() + comment + System.lineSeparator()
                            + content + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return new ActivationOutcome(true, true,
                    "Added " + toolName + " activation to " + file + " — " + restartHint);
        } catch (IOException e) {
            return new ActivationOutcome(false, false, "Could not update " + file + ": " + e.getMessage());
        }
    }
}
